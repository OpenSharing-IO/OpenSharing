package io.opensharing.asset.table;

import io.opensharing.ObjectNames;
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
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.catalog.StorageOperation;
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
import java.util.function.Supplier;

/**
 * The tables a recipient reads through the sharing protocol in a share it holds SELECT on: the
 * tables the share includes one by one, plus the tables the catalog currently lists under each
 * schema the share includes as a whole.
 *
 * <p>Every catalog call is made as the share's owner, so the catalog decides whether the owner may
 * still read each table.
 */
public class ProtocolTables {

  private static final int CATALOG_PAGE_SIZE = 1000;

  private final CatalogConnector catalog;
  private final DeltaKernel kernel;
  private final DeltaTableMetadataReader metadataReader;
  private final DeltaTableQueryReader queryReader;
  private final ProtocolShares shares;
  private final SharedDataObjectStore objects;

  public ProtocolTables(
      CatalogConnector catalog,
      DeltaKernel kernel,
      DeltaTableMetadataReader metadataReader,
      DeltaTableQueryReader queryReader,
      ProtocolShares shares,
      SharedDataObjectStore objects) {
    this.catalog = catalog;
    this.kernel = kernel;
    this.metadataReader = metadataReader;
    this.queryReader = queryReader;
    this.shares = shares;
    this.objects = objects;
  }

  /** Lists one page of the tables in one schema of the share, by name. */
  public ListResponse<TableResponse> list(
      RecipientPrincipal recipient,
      String share,
      String schema,
      Integer maxResults,
      String pageToken) {
    ShareEntity granted = shares.requireGranted(recipient, share);
    return page(granted, listed(granted, inSchema(granted, schema)), maxResults, pageToken);
  }

  /** Lists one page of all the tables in the share, by schema and then name. */
  public ListResponse<TableResponse> listAll(
      RecipientPrincipal recipient, String share, Integer maxResults, String pageToken) {
    ShareEntity granted = shares.requireGranted(recipient, share);
    return page(granted, listed(granted, objects.list(granted)), maxResults, pageToken);
  }

  /**
   * The Delta version of one table in the share: the latest, or with an ISO-8601 {@code
   * startingTimestamp} the earliest version committed at or after it.
   */
  public long version(
      RecipientPrincipal recipient,
      String share,
      String schema,
      String table,
      String startingTimestamp) {
    ShareEntity granted = shares.requireGranted(recipient, share);
    Instant timestamp = parseTimestamp("startingTimestamp", startingTimestamp);
    return kernel.getVersion(resolve(granted, schema, table), timestamp, owner(granted));
  }

  /**
   * The protocol and metadata of one table in the share, as of the latest version, {@code
   * version}, or an ISO-8601 {@code timestamp}, in the response format {@code capabilities} asks
   * for.
   */
  public DeltaTableMetadataReader.Result metadata(
      RecipientPrincipal recipient,
      String share,
      String schema,
      String table,
      Long version,
      String timestamp,
      String capabilities) {
    ShareEntity granted = shares.requireGranted(recipient, share);
    Instant asOf = parseTimestamp("timestamp", timestamp);
    return metadataReader.read(
        resolve(granted, schema, table), version, asOf, owner(granted), capabilities);
  }

  /**
   * Read credentials for one table in the share that supports directory access, scoped to its root
   * or to the auxiliary location {@code request} names. The catalog vends them to the share owner.
   */
  public TemporaryTableCredentialsResponse temporaryCredentials(
      RecipientPrincipal recipient,
      String share,
      String schema,
      String table,
      TemporaryTableCredentialsRequest request) {
    ShareEntity granted = shares.requireGranted(recipient, share);
    ResolvedAsset resolved = resolve(granted, schema, table);
    List<String> accessModes = TableAccessModes.forTable(resolved);
    if (accessModes == null || !accessModes.contains("dir")) {
      throw ApiException.invalidParameter(
          "table '" + schema + "." + table + "' does not support directory access");
    }
    String location = credentialLocation(resolved, request == null ? null : request.location());
    CredentialRequest credentials =
        new CredentialRequest(
            AssetType.TABLE,
            resolved.fullName(),
            resolved.catalogAssetId(),
            location,
            StorageOperation.READ,
            null);
    StorageCredentials minted =
        catalog.getStorageCredentials(credentials, owner(granted)).stream()
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
   * URLs. Pushdown hints and startingVersion queries are not implemented.
   */
  public DeltaTableQueryReader.Result query(
      RecipientPrincipal recipient,
      String share,
      String schema,
      String table,
      QueryTableRequest request,
      DeltaTableQueryReader.ResponseOptions options) {
    ShareEntity granted = shares.requireGranted(recipient, share);
    ResolvedAsset resolved = resolve(granted, schema, table);
    List<String> accessModes = TableAccessModes.forTable(resolved);
    if (accessModes == null || !accessModes.contains("url")) {
      throw ApiException.invalidParameter(
          "table '" + schema + "." + table + "' does not support url access");
    }
    QueryTableRequest body = request == null ? QueryTableRequest.EMPTY : request;
    if (body.startingVersion() != null || body.endingVersion() != null) {
      throw ApiException.notImplemented("startingVersion queries are not supported");
    }
    if (hasPushdownHint(body)) {
      throw ApiException.notImplemented("predicate and limit pushdown is not supported");
    }
    return queryReader.read(
        resolved,
        owner(granted),
        options,
        body.version(),
        parseTimestamp("timestamp", body.timestamp()));
  }

  private static boolean hasPushdownHint(QueryTableRequest body) {
    return (body.predicateHints() != null && !body.predicateHints().isEmpty())
        || (body.jsonPredicateHints() != null && !body.jsonPredicateHints().isBlank())
        || body.limitHint() != null;
  }

  private List<SharedDataObjectEntity> inSchema(ShareEntity share, String schema) {
    List<SharedDataObjectEntity> included =
        objects.listInSchema(share, ObjectNames.normalize(schema));
    if (included.isEmpty()) {
      throw ApiException.notFound(
          "schema '" + schema + "' does not exist in share '" + share.getName() + "'");
    }
    return included;
  }

  private ResolvedAsset resolve(ShareEntity share, String schema, String table) {
    String name = ObjectNames.normalize(table);
    return listed(share, inSchema(share, schema)).stream()
        .filter(listed -> name.equals(ObjectNames.normalize(listed.name())))
        .findFirst()
        .map(listed -> listed.resolved().get())
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "table '"
                        + schema
                        + "."
                        + table
                        + "' does not exist in share '"
                        + share.getName()
                        + "'"));
  }

  private static String credentialLocation(ResolvedAsset table, String requested) {
    AssetLocation location = table.location();
    String root = location == null ? null : location.storageLocation();
    if (requested == null || requested.isBlank()) {
      if (root == null || root.isBlank()) {
        throw ApiException.invalidParameter(
            "table '" + table.fullName() + "' has no storage location");
      }
      return root;
    }
    if (requested.equals(root)
        || (location != null && location.auxiliaryLocations().contains(requested))) {
      return requested;
    }
    throw ApiException.invalidParameter(
        "location '" + requested + "' is not part of table '" + table.fullName() + "'");
  }

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

  /**
   * A table on the listing. Included tables are resolved only when they land on the requested
   * page; catalog children arrive resolved.
   */
  private record Listed(String schema, String name, String id, Supplier<ResolvedAsset> resolved) {}

  private List<Listed> listed(ShareEntity share, List<SharedDataObjectEntity> included) {
    List<Listed> tables = new ArrayList<>();
    for (SharedDataObjectEntity object : included) {
      String schema = object.getSharedAsSchema();
      if (object.getType() == AssetType.SCHEMA) {
        for (ResolvedAsset child : tablesIn(share, object)) {
          String name = lastSegment(child.fullName());
          tables.add(new Listed(schema, name, child.catalogAssetId(), () -> child));
        }
      } else if (object.getType() == AssetType.TABLE) {
        Asset asset = new Asset(AssetType.TABLE, object.getName());
        tables.add(
            new Listed(
                schema,
                object.getSharedAsTable(),
                object.getSourceAssetId(),
                () -> catalog.resolveAsset(asset, owner(share))));
      }
    }
    tables.sort(
        Comparator.comparing(Listed::schema)
            .thenComparing(table -> ObjectNames.normalize(table.name())));
    return tables;
  }

  private ListResponse<TableResponse> page(
      ShareEntity share, List<Listed> tables, Integer maxResults, String pageToken) {
    return Pagination.page(
        maxResults,
        pageToken,
        (offset, limit) -> {
          int from = Math.min(offset, tables.size());
          return tables.subList(from, from + Math.min(limit, tables.size() - from));
        },
        table -> response(share, table));
  }

  private List<ResolvedAsset> tablesIn(ShareEntity share, SharedDataObjectEntity schema) {
    Asset parent = new Asset(AssetType.SCHEMA, schema.getName());
    List<ResolvedAsset> tables = new ArrayList<>();
    String pageToken = null;
    do {
      AssetPage page = catalog.listChildren(parent, CATALOG_PAGE_SIZE, pageToken, owner(share));
      page.assets().stream().filter(asset -> asset.type() == AssetType.TABLE).forEach(tables::add);
      pageToken = page.nextPageToken();
    } while (pageToken != null);
    return tables;
  }

  private static TableResponse response(ShareEntity share, Listed table) {
    ResolvedAsset resolved = table.resolved().get();
    AssetLocation location = resolved.location();
    return new TableResponse(
        table.name(),
        table.schema(),
        share.getName(),
        share.getId(),
        table.id(),
        location == null ? null : location.storageLocation(),
        location == null || location.auxiliaryLocations().isEmpty()
            ? null
            : location.auxiliaryLocations(),
        TableAccessModes.forTable(resolved));
  }

  private static AuthContext owner(ShareEntity share) {
    return AuthContext.of(UserContext.fromUserIdAndName(share.getOwnerId(), null));
  }

  private static String lastSegment(String fullName) {
    return fullName.substring(fullName.lastIndexOf('.') + 1);
  }
}
