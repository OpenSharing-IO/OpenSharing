package io.opensharing.asset.table;

import io.opensharing.ObjectNames;
import io.opensharing.asset.SharedAssetResolver;
import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.asset.SharedDataObjectStore;
import io.opensharing.asset.share.ProtocolShares;
import io.opensharing.asset.table.delta.DeltaKernel;
import io.opensharing.asset.table.delta.DeltaTableMetadataReader;
import io.opensharing.asset.table.delta.DeltaTableQueryReader;
import io.opensharing.auth.AuthContext;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.Asset;
import io.opensharing.catalog.AssetLocation;
import io.opensharing.catalog.AssetPage;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.CredentialRequest;
import io.opensharing.catalog.DataSourceFormat;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.catalog.StorageOperation;
import io.opensharing.catalog.TableProperties;
import io.opensharing.exception.AssetNotFoundException;
import io.opensharing.exception.CatalogException;
import io.opensharing.http.ApiException;
import io.opensharing.http.ListResponse;
import io.opensharing.http.Pagination;
import io.opensharing.recipient.RecipientPrincipal;
import io.opensharing.share.ShareEntity;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The tables a recipient reads through the sharing protocol in a share it holds SELECT on: the
 * tables the share includes one by one, plus the tables the catalog currently lists under each
 * schema the share includes as a whole. Listing serves included tables as the share recorded them;
 * reading a table resolves it in the catalog.
 *
 * <p>Every catalog call is made as the share's owner, so the catalog decides whether the owner may
 * still read each table.
 *
 * <p>Callers pass the share name, schema alias and table alias normalized.
 */
public class ProtocolTables {

  private static final Logger log = LoggerFactory.getLogger(ProtocolTables.class);
  private static final int CATALOG_PAGE_SIZE = 1000;

  private final CatalogConnector catalog;
  private final DeltaKernel kernel;
  private final DeltaTableMetadataReader metadataReader;
  private final DeltaTableQueryReader queryReader;
  private final ProtocolShares protocolShares;
  private final SharedDataObjectStore sharedObjects;

  public ProtocolTables(
      CatalogConnector catalog,
      DeltaKernel kernel,
      DeltaTableMetadataReader metadataReader,
      DeltaTableQueryReader queryReader,
      ProtocolShares protocolShares,
      SharedDataObjectStore sharedObjects) {
    this.catalog = catalog;
    this.kernel = kernel;
    this.metadataReader = metadataReader;
    this.queryReader = queryReader;
    this.protocolShares = protocolShares;
    this.sharedObjects = sharedObjects;
  }

  /** Lists one page of the tables under one schema alias of the share, by table alias. */
  public ListResponse<TableResponse> list(
      RecipientPrincipal recipient,
      String shareName,
      String schemaAlias,
      Integer maxResults,
      String pageToken) {
    ShareEntity share = protocolShares.requireGranted(recipient, shareName);
    List<SharedDataObjectEntity> schemaObjects = sharedObjects.listInSchema(share, schemaAlias);
    if (schemaObjects.isEmpty()) {
      throw ApiException.notFound(
          "schema '" + schemaAlias + "' does not exist in share '" + share.getName() + "'");
    }
    return page(listTables(share, schemaObjects), maxResults, pageToken);
  }

  /** Lists one page of all the tables in the share, by schema alias and then table alias. */
  public ListResponse<TableResponse> listAll(
      RecipientPrincipal recipient, String shareName, Integer maxResults, String pageToken) {
    ShareEntity share = protocolShares.requireGranted(recipient, shareName);
    return page(listTables(share, sharedObjects.list(share)), maxResults, pageToken);
  }

  /** Gets one table of the share by its schema alias and table alias. */
  public GetTableResponse get(
      RecipientPrincipal recipient, String shareName, String schemaAlias, String tableAlias) {
    ShareEntity share = protocolShares.requireGranted(recipient, shareName);
    SharedTable table = findTable(share, schemaAlias, tableAlias);
    DataSourceFormat format = dataSourceFormat(table.catalogTable());
    return new GetTableResponse(
        table.tableAlias(),
        table.schemaAlias(),
        share.getName(),
        share.getId(),
        table.tableId(),
        format == null ? null : format.wireName());
  }

  /**
   * The Delta version of one table in the share: the latest, or with an ISO-8601 {@code
   * startingTimestamp} the earliest version committed at or after it.
   */
  public long version(
      RecipientPrincipal recipient,
      String shareName,
      String schemaAlias,
      String tableAlias,
      String startingTimestamp) {
    ShareEntity share = protocolShares.requireGranted(recipient, shareName);
    Instant timestamp = parseTimestamp("startingTimestamp", startingTimestamp);
    return kernel.getVersion(
        resolveTable(share, schemaAlias, tableAlias), timestamp, owner(share));
  }

  /**
   * The protocol and metadata of one table in the share, as of the latest version, {@code
   * version}, or an ISO-8601 {@code timestamp}, in the response format {@code capabilities} asks
   * for.
   */
  public DeltaTableMetadataReader.Result metadata(
      RecipientPrincipal recipient,
      String shareName,
      String schemaAlias,
      String tableAlias,
      Long version,
      String timestamp,
      String capabilities) {
    ShareEntity share = protocolShares.requireGranted(recipient, shareName);
    Instant asOf = parseTimestamp("timestamp", timestamp);
    return metadataReader.read(
        resolveTable(share, schemaAlias, tableAlias), version, asOf, owner(share), capabilities);
  }

  /**
   * Read credentials for one table in the share that supports directory access, scoped to its root
   * or to the auxiliary location {@code request} names. The catalog vends them to the share owner.
   */
  public TemporaryTableCredentialsResponse temporaryCredentials(
      RecipientPrincipal recipient,
      String shareName,
      String schemaAlias,
      String tableAlias,
      TemporaryTableCredentialsRequest request) {
    ShareEntity share = protocolShares.requireGranted(recipient, shareName);
    ResolvedAsset catalogTable = resolveTable(share, schemaAlias, tableAlias);
    List<String> accessModes = TableAccessModes.forTable(catalogTable);
    if (accessModes == null || !accessModes.contains("dir")) {
      throw ApiException.invalidParameter(
          "table '" + schemaAlias + "." + tableAlias + "' does not support directory access");
    }
    String location =
        credentialLocation(catalogTable, request == null ? null : request.location());
    CredentialRequest credentials =
        new CredentialRequest(
            AssetType.TABLE,
            catalogTable.fullName(),
            catalogTable.catalogAssetId(),
            location,
            StorageOperation.READ,
            null);
    StorageCredentials minted =
        catalog.getStorageCredentials(credentials, owner(share)).stream()
            .filter(candidate -> candidate.covers(location))
            .findFirst()
            .orElseThrow(
                () ->
                    new CatalogException(
                        "catalog returned no credentials for location '" + location + "'"));
    return new TemporaryTableCredentialsResponse(TemporaryCredentials.from(minted));
  }

  /**
   * One snapshot of a table in the share that supports url access, as of the latest version,
   * {@code version}, or an ISO-8601 {@code timestamp} in {@code request}, with presigned file
   * URLs. A latest-snapshot query can ask for a refresh token, and a later query can pass it back
   * to re-sign the same version. jsonPredicateHints and limitHint narrow the files returned.
   * {@code startingVersion}, with an optional {@code endingVersion}, returns the data change files
   * committed in that range instead.
   */
  public DeltaTableQueryReader.Result query(
      RecipientPrincipal recipient,
      String shareName,
      String schemaAlias,
      String tableAlias,
      QueryTableRequest request,
      DeltaTableQueryReader.ResponseOptions options) {
    ShareEntity share = protocolShares.requireGranted(recipient, shareName);
    ResolvedAsset catalogTable =
        requireUrlAccess(resolveTable(share, schemaAlias, tableAlias), schemaAlias, tableAlias);
    QueryTableRequest body = request == null ? QueryTableRequest.EMPTY : request;
    if (body.startingVersion() != null || body.endingVersion() != null) {
      return queryChanges(body, catalogTable, share, options);
    }
    boolean historical = body.version() != null || body.timestamp() != null;
    boolean includeRefreshToken = Boolean.TRUE.equals(body.includeRefreshToken());
    if (includeRefreshToken && historical) {
      throw ApiException.invalidParameter(
          "includeRefreshToken cannot be used when querying a specific version.");
    }
    if (body.refreshToken() != null && historical) {
      throw ApiException.invalidParameter(
          "refreshToken cannot be used when querying a specific version.");
    }
    if (body.predicateHints() != null && !body.predicateHints().isEmpty()) {
      log.debug("Ignoring deprecated predicateHints {}", body.predicateHints());
    }
    return queryReader.read(
        catalogTable,
        owner(share),
        options,
        body.version(),
        parseTimestamp("timestamp", body.timestamp()),
        body.refreshToken(),
        includeRefreshToken,
        body.jsonPredicateHints(),
        limitHint(body));
  }

  /**
   * The Change Data Feed of a table in the share that supports url access and records it, from
   * {@code startingVersion} or an ISO-8601 {@code startingTimestamp} through {@code endingVersion},
   * {@code endingTimestamp}, or the latest version, with presigned file URLs.
   */
  public DeltaTableQueryReader.Result changes(
      RecipientPrincipal recipient,
      String shareName,
      String schemaAlias,
      String tableAlias,
      TableChangesRequest request,
      DeltaTableQueryReader.ResponseOptions options) {
    ShareEntity share = protocolShares.requireGranted(recipient, shareName);
    ResolvedAsset catalogTable =
        requireUrlAccess(resolveTable(share, schemaAlias, tableAlias), schemaAlias, tableAlias);
    return queryReader.readChangeDataFeed(
        catalogTable,
        owner(share),
        options,
        request.startingVersion(),
        parseTimestamp("startingTimestamp", request.startingTimestamp()),
        request.endingVersion(),
        parseTimestamp("endingTimestamp", request.endingTimestamp()),
        Boolean.TRUE.equals(request.includeHistoricalMetadata()),
        Boolean.TRUE.equals(request.includeHistoricalProtocol()));
  }

  private static ResolvedAsset requireUrlAccess(
      ResolvedAsset catalogTable, String schemaAlias, String tableAlias) {
    List<String> accessModes = TableAccessModes.forTable(catalogTable);
    if (accessModes == null || !accessModes.contains("url")) {
      throw ApiException.invalidParameter(
          "table '" + schemaAlias + "." + tableAlias + "' does not support url access");
    }
    return catalogTable;
  }

  /**
   * Reads the data change files from {@code startingVersion}. Predicate and limit hints apply to
   * snapshot files only, so they are ignored here.
   */
  private DeltaTableQueryReader.Result queryChanges(
      QueryTableRequest body,
      ResolvedAsset table,
      ShareEntity share,
      DeltaTableQueryReader.ResponseOptions options) {
    Long startingVersion = body.startingVersion();
    Long endingVersion = body.endingVersion();
    if (startingVersion == null) {
      throw ApiException.invalidParameter("endingVersion requires startingVersion");
    }
    if (body.version() != null || body.timestamp() != null) {
      throw ApiException.invalidParameter(
          "Only one of version, timestamp, and startingVersion can be set");
    }
    if (Boolean.TRUE.equals(body.includeRefreshToken()) || body.refreshToken() != null) {
      throw ApiException.invalidParameter(
          "includeRefreshToken and refreshToken cannot be used with startingVersion");
    }
    if (startingVersion < 0) {
      throw ApiException.invalidParameter("startingVersion must be non-negative");
    }
    if (endingVersion != null && endingVersion < startingVersion) {
      throw ApiException.invalidParameter(
          "endingVersion must be greater than or equal to startingVersion");
    }
    return queryReader.readChanges(
        table,
        owner(share),
        options,
        startingVersion,
        endingVersion,
        Boolean.TRUE.equals(body.includeHistoricalProtocol()));
  }

  /**
   * The limit hint to push down. It counts unfiltered file records, so it is dropped when any
   * predicate hint is present.
   */
  private static Long limitHint(QueryTableRequest body) {
    boolean hasPredicate =
        (body.predicateHints() != null && !body.predicateHints().isEmpty())
            || (body.jsonPredicateHints() != null && !body.jsonPredicateHints().isBlank());
    return hasPredicate || body.limitHint() == null ? null : body.limitHint().longValue();
  }

  /**
   * The shared table, or the catalog table in a shared schema, under {@code schemaAlias} and
   * {@code tableAlias}.
   */
  private SharedTable findTable(ShareEntity share, String schemaAlias, String tableAlias) {
    Optional<SharedDataObjectEntity> sharedTable =
        sharedObjects.findByAlias(share, schemaAlias, tableAlias);
    if (sharedTable.isPresent()) {
      ResolvedAsset catalogTable = resolveSharedAsset(share, sharedTable.get());
      if (catalogTable != null) {
        return new SharedTable(
            schemaAlias, tableAlias, sharedTable.get().getSourceAssetId(), catalogTable);
      }
    } else {
      Optional<SharedDataObjectEntity> sharedSchema =
          sharedObjects.findByAlias(share, schemaAlias, "");
      if (sharedSchema.isPresent()
          && !tableAlias.contains(".")
          && resolveSharedAsset(share, sharedSchema.get()) != null) {
        Asset childTable =
            new Asset(AssetType.TABLE, sharedSchema.get().getFullName() + "." + tableAlias);
        try {
          ResolvedAsset catalogTable = catalog.resolveAsset(childTable, owner(share));
          return new SharedTable(
              schemaAlias, alias(catalogTable), catalogTable.catalogAssetId(), catalogTable);
        } catch (AssetNotFoundException notInCatalog) {
          // Falls through to the alias-only not-found below.
        }
      }
    }
    // Recipients see aliases only, so the catalog's own not-found message must not reach them.
    throw ApiException.notFound(
        "table '"
            + schemaAlias
            + "."
            + tableAlias
            + "' does not exist in share '"
            + share.getName()
            + "'");
  }

  /** The catalog table shared under {@code tableAlias}, resolved as the share's owner. */
  private ResolvedAsset resolveTable(ShareEntity share, String schemaAlias, String tableAlias) {
    return findTable(share, schemaAlias, tableAlias).catalogTable();
  }

  /**
   * The location to vend credentials for: the table's root when none is requested, otherwise {@code
   * requested} when it is the root or one of the table's auxiliary locations.
   */
  private static String credentialLocation(ResolvedAsset table, String requested) {
    AssetLocation location = table.location();
    String root = location == null ? null : location.storageLocation();
    if (requested == null || requested.isBlank()) {
      if (root == null || root.isBlank()) {
        log.error("Catalog table '{}' has no storage location", table.fullName());
        throw new CatalogException("the catalog reported no storage location for the table");
      }
      return root;
    }
    if (requested.equals(root)
        || (location != null && location.auxiliaryLocations().contains(requested))) {
      return requested;
    }
    throw ApiException.invalidParameter("location '" + requested + "' is not part of the table");
  }

  /** The ISO-8601 UTC timestamp in the parameter {@code name}, or null when absent. */
  private static Instant parseTimestamp(String name, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException invalid) {
      throw ApiException.invalidParameter(name + " must be an ISO-8601 UTC timestamp");
    }
  }

  /** A table of the share, with the catalog table it resolved to. */
  private record SharedTable(
      String schemaAlias, String tableAlias, String tableId, ResolvedAsset catalogTable) {}

  /** The tables {@code sharedObjects} puts on the listing, by schema alias and table alias. */
  private List<TableResponse> listTables(
      ShareEntity share, List<SharedDataObjectEntity> sharedObjects) {
    List<TableResponse> tables = new ArrayList<>();
    for (SharedDataObjectEntity sharedObject : sharedObjects) {
      String schemaAlias = sharedObject.getSharedAsSchema();
      if (sharedObject.getType() == AssetType.SCHEMA) {
        // The share records no tables under a shared schema, so only the catalog can list them.
        for (ResolvedAsset catalogTable : catalogTablesIn(share, sharedObject)) {
          AssetLocation location = catalogTable.location();
          tables.add(
              new TableResponse(
                  alias(catalogTable),
                  schemaAlias,
                  share.getName(),
                  share.getId(),
                  catalogTable.catalogAssetId(),
                  location == null ? null : location.storageLocation(),
                  location == null || location.auxiliaryLocations().isEmpty()
                      ? null
                      : location.auxiliaryLocations(),
                  TableAccessModes.forFormat(dataSourceFormat(catalogTable))));
        }
      } else if (sharedObject.getType() == AssetType.TABLE) {
        // The share recorded everything listed about an included table, so the catalog isn't
        // called; Get Table resolves it.
        tables.add(
            new TableResponse(
                sharedObject.getSharedAsTable(),
                schemaAlias,
                share.getName(),
                share.getId(),
                sharedObject.getSourceAssetId(),
                sharedObject.getStorageLocation(),
                sharedObject.getAuxiliaryLocations().isEmpty()
                    ? null
                    : sharedObject.getAuxiliaryLocations(),
                TableAccessModes.forFormat(sharedObject.getSourceFormat())));
      }
    }
    tables.sort(Comparator.comparing(TableResponse::schema).thenComparing(TableResponse::name));
    return tables;
  }

  /**
   * Resolves {@code sharedObject} in the catalog, or logs an error and returns null once the
   * catalog dropped or replaced it.
   */
  private ResolvedAsset resolveSharedAsset(ShareEntity share, SharedDataObjectEntity sharedObject) {
    Optional<ResolvedAsset> catalogAsset =
        SharedAssetResolver.resolve(catalog, sharedObject, owner(share));
    if (catalogAsset.isEmpty()) {
      String type = sharedObject.getType().name().toLowerCase(Locale.ROOT);
      log.error(
          "Hiding {} '{}' in share '{}': catalog {} '{}' was dropped or replaced since it was"
              + " shared",
          type,
          sharedObject.getSharedAs(),
          share.getName(),
          type,
          sharedObject.getFullName());
    }
    return catalogAsset.orElse(null);
  }

  /** One page of {@code tables}. */
  private static ListResponse<TableResponse> page(
      List<TableResponse> tables, Integer maxResults, String pageToken) {
    return Pagination.protocolPage(
        maxResults,
        pageToken,
        (offset, limit) -> {
          int fromIndex = Math.min(offset, tables.size());
          return tables.subList(fromIndex, fromIndex + Math.min(limit, tables.size() - fromIndex));
        },
        Function.identity());
  }

  /**
   * Every table the catalog lists under the included {@code sharedSchema}, across all pages, or
   * none once the catalog has dropped the schema.
   */
  private List<ResolvedAsset> catalogTablesIn(
      ShareEntity share, SharedDataObjectEntity sharedSchema) {
    Asset catalogSchema = new Asset(AssetType.SCHEMA, sharedSchema.getFullName());
    List<ResolvedAsset> catalogTables = new ArrayList<>();
    String catalogPageToken = null;
    do {
      AssetPage catalogPage;
      try {
        catalogPage =
            catalog.listChildren(catalogSchema, CATALOG_PAGE_SIZE, catalogPageToken, owner(share));
      } catch (AssetNotFoundException dropped) {
        log.error(
            "Hiding the tables of schema '{}' in share '{}': catalog schema '{}' was dropped",
            sharedSchema.getSharedAs(),
            share.getName(),
            sharedSchema.getFullName());
        return List.of();
      }
      catalogPage.assets().stream()
          .filter(child -> child.type() == AssetType.TABLE)
          .forEach(catalogTables::add);
      catalogPageToken = catalogPage.nextPageToken();
    } while (catalogPageToken != null);
    return catalogTables;
  }

  /** The table's format, or null when the catalog reports none. */
  private static DataSourceFormat dataSourceFormat(ResolvedAsset catalogTable) {
    return catalogTable.additionalProperties() instanceof TableProperties tableProperties
        ? tableProperties.dataSourceFormat()
        : null;
  }

  /** The share's owner, whom every catalog call acts as. */
  private static AuthContext owner(ShareEntity share) {
    return AuthContext.of(UserContext.fromUserIdAndName(share.getOwnerId(), null));
  }

  /**
   * The alias recipients see for a catalog table in an included schema: its table name, lowercased
   * like every alias.
   */
  private static String alias(ResolvedAsset catalogTable) {
    String fullName = catalogTable.fullName();
    return ObjectNames.normalize(fullName.substring(fullName.lastIndexOf('.') + 1));
  }
}
