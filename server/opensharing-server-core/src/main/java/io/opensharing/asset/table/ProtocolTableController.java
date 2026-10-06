package io.opensharing.asset.table;

import io.opensharing.ObjectNames;
import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.asset.SharedDataObjectStore;
import io.opensharing.asset.table.delta.DeltaKernel;
import io.opensharing.asset.table.delta.DeltaTableMetadataReader;
import io.opensharing.asset.table.delta.DeltaTableQueryReader;
import io.opensharing.auth.AuthContext;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.AssetLookup;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.CredentialRequest;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.catalog.StorageOperation;
import io.opensharing.config.OpenSharingProperties;
import io.opensharing.exception.CatalogException;
import io.opensharing.http.ApiException;
import io.opensharing.http.ListResponse;
import io.opensharing.http.Listings;
import io.opensharing.recipient.RecipientPrincipal;
import io.opensharing.recipient.RecipientStore;
import io.opensharing.share.ShareEntity;
import io.opensharing.share.SharePermissionStore;
import io.opensharing.share.ShareStore;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Recipient protocol API for tables in a SELECT-granted share. */
@RestController
@RequestMapping(
    value = "${opensharing.protocol-prefix}/shares/{share}",
    produces = "application/json;charset=UTF-8")
public class ProtocolTableController {

  private static final Logger log = LoggerFactory.getLogger(ProtocolTableController.class);

  private final RecipientStore recipients;
  private final ShareStore shares;
  private final SharePermissionStore permissions;
  private final SharedDataObjectStore objects;
  private final CatalogConnector catalog;
  private final DeltaKernel kernel;
  private final DeltaTableMetadataReader metadataReader;
  private final DeltaTableQueryReader queryReader;
  private final Listings listings;
  private final OpenSharingProperties properties;

  public ProtocolTableController(
      RecipientStore recipients,
      ShareStore shares,
      SharePermissionStore permissions,
      SharedDataObjectStore objects,
      CatalogConnector catalog,
      DeltaKernel kernel,
      DeltaTableMetadataReader metadataReader,
      DeltaTableQueryReader queryReader,
      Listings listings,
      OpenSharingProperties properties) {
    this.recipients = recipients;
    this.shares = shares;
    this.permissions = permissions;
    this.objects = objects;
    this.catalog = catalog;
    this.kernel = kernel;
    this.metadataReader = metadataReader;
    this.queryReader = queryReader;
    this.listings = listings;
    this.properties = properties;
  }

  @GetMapping("/all-tables")
  public ListResponse<TableResponse> listAll(
      RecipientPrincipal principal,
      @PathVariable String share,
      @RequestParam(required = false) Integer maxResults,
      @RequestParam(required = false) String pageToken) {
    ShareEntity entity = requireGrantedShare(principal, share);
    return listings.page(
        maxResults, pageToken, pageable -> listAll(entity, pageable), listed -> listed);
  }

  @GetMapping("/schemas/{schema}/tables")
  public ListResponse<TableResponse> list(
      RecipientPrincipal principal,
      @PathVariable String share,
      @PathVariable String schema,
      @RequestParam(required = false) Integer maxResults,
      @RequestParam(required = false) String pageToken) {
    ShareEntity entity = requireGrantedShare(principal, share);
    return listings.page(
        maxResults, pageToken, pageable -> listInSchema(entity, schema, pageable), listed -> listed);
  }

  @GetMapping("/schemas/{schema}/tables/{table}/version")
  public ResponseEntity<Void> version(
      RecipientPrincipal principal,
      @PathVariable String share,
      @PathVariable String schema,
      @PathVariable String table,
      @RequestParam(required = false) String startingTimestamp) {
    ShareEntity entity = requireGrantedShare(principal, share);
    long version =
        kernel.getVersion(
            resolveTable(entity, schema, table), parseTimestamp(startingTimestamp), owner(entity));
    return ResponseEntity.ok().header("Delta-Table-Version", Long.toString(version)).build();
  }

  @GetMapping(
      value = "/schemas/{schema}/tables/{table}/metadata",
      produces = "application/x-ndjson;charset=UTF-8")
  public ResponseEntity<String> metadata(
      RecipientPrincipal principal,
      @PathVariable String share,
      @PathVariable String schema,
      @PathVariable String table,
      @RequestParam(required = false) Long version,
      @RequestParam(required = false) String timestamp,
      @RequestHeader(value = "delta-sharing-capabilities", required = false) String capabilities) {
    ShareEntity entity = requireGrantedShare(principal, share);
    DeltaTableMetadataReader.Result result =
        metadataReader.read(
            resolveTable(entity, schema, table),
            version,
            parseTimestamp(timestamp),
            owner(entity),
            capabilities);
    return ResponseEntity.ok()
        .header("Delta-Table-Version", Long.toString(result.version()))
        .header(DeltaSharingCapabilities.HEADER, DeltaSharingCapabilities.responded(capabilities))
        .contentType(MediaType.parseMediaType("application/x-ndjson;charset=UTF-8"))
        .body(result.ndjson());
  }

  @PostMapping("/schemas/{schema}/tables/{table}/temporary-table-credentials")
  public TemporaryTableCredentialsResponse temporaryCredentials(
      RecipientPrincipal principal,
      @PathVariable String share,
      @PathVariable String schema,
      @PathVariable String table,
      @RequestBody(required = false) TemporaryTableCredentialsRequest request) {
    ShareEntity entity = requireGrantedShare(principal, share);
    ResolvedAsset resolved = resolveTable(entity, schema, table);
    List<String> accessModes = TableAccessModes.forTable(resolved);
    if (accessModes == null || !accessModes.contains("dir")) {
      throw ApiException.invalidParameter(
          "table '" + schema + "." + table + "' does not support directory access");
    }
    String location = credentialLocation(resolved, request);
    List<StorageCredentials> minted =
        catalog.getStorageCredentials(
            new CredentialRequest(
                AssetType.TABLE,
                resolved.identifier(),
                resolved.catalogAssetId(),
                location,
                StorageOperation.READ,
                properties.getAssetCredentials().getTtl()),
            owner(entity));
    StorageCredentials matching =
        minted.stream()
            .filter(candidate -> covers(candidate.prefix(), location))
            .findFirst()
            .orElseThrow(
                () ->
                    new CatalogException(
                        "catalog returned no credentials for table root '" + location + "'"));
    return new TemporaryTableCredentialsResponse(TemporaryCredentials.from(matching));
  }

  // Snapshot Query Table only. startingVersion/endingVersion are NOT_IMPLEMENTED.
  @PostMapping(
      value = "/schemas/{schema}/tables/{table}/query",
      produces = "application/x-ndjson;charset=UTF-8")
  public ResponseEntity<String> query(
      RecipientPrincipal principal,
      @PathVariable String share,
      @PathVariable String schema,
      @PathVariable String table,
      @RequestBody(required = false) QueryTableRequest request,
      @RequestHeader(value = "delta-sharing-capabilities", required = false) String capabilities,
      @RequestHeader(value = FileIdHash.HEADER, required = false) String fileIdHashHeader) {
    ShareEntity entity = requireGrantedShare(principal, share);
    ResolvedAsset resolved = requireUrlAccess(resolveTable(entity, schema, table), schema, table);
    QueryTableRequest body = request == null ? QueryTableRequest.EMPTY : request;
    var options = responseOptions(capabilities, fileIdHashHeader);
    if (body.startingVersion() != null || body.endingVersion() != null) {
      throw ApiException.notImplemented("startingVersion queries are not supported");
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
    Long version = body.version();
    if (body.refreshToken() != null && !body.refreshToken().isBlank()) {
      version = RefreshTokens.versionOf(body.refreshToken(), resolved.identifier());
    }
    boolean includeRefreshToken = Boolean.TRUE.equals(body.includeRefreshToken());
    boolean includeEndStreamAction = DeltaSharingCapabilities.includeEndStreamAction(capabilities);
    if (body.predicateHints() != null && !body.predicateHints().isEmpty()) {
      log.debug("Ignoring deprecated predicateHints {}", body.predicateHints());
    }
    DeltaTableQueryReader.Result result =
        queryReader.read(
            resolved,
            owner(entity),
            capabilities,
            fileIdHash,
            historical,
            includeRefreshToken,
            includeEndStreamAction,
            body.jsonPredicateHints(),
            limitHint(body));
    var response =
        ResponseEntity.ok()
            .header("Delta-Table-Version", Long.toString(result.version()))
            .header(
                DeltaSharingCapabilities.HEADER,
                DeltaSharingCapabilities.responded(
                    options.capabilities(), options.includeEndStreamAction()))
            .contentType(MediaType.parseMediaType("application/x-ndjson;charset=UTF-8"));
    if (options.fileIdHash() != null) {
      response = response.header(FileIdHash.HEADER, options.fileIdHash());
    }
    return response.body(result.ndjson());
  }

  private static String credentialLocation(
      ResolvedAsset table, TemporaryTableCredentialsRequest request) {
    String location = request == null ? null : request.location();
    if (location == null || location.isBlank()) {
      if (table.storageLocation() == null || table.storageLocation().isBlank()) {
        throw ApiException.invalidParameter(
            "table '" + table.identifier() + "' has no storage location");
      }
      return table.storageLocation();
    }
    if (location.equals(table.storageLocation())
        || table.auxiliaryLocations().contains(location)) {
      return location;
    }
    throw ApiException.invalidParameter(
        "location '" + location + "' is not part of table '" + table.identifier() + "'");
  }

  private static boolean covers(String prefix, String location) {
    if (prefix == null || prefix.isBlank()) {
      return false;
    }
    String normalized = prefix.endsWith("/") ? prefix : prefix + "/";
    return location.equals(prefix) || location.startsWith(normalized);
  }

  private ShareEntity requireGrantedShare(RecipientPrincipal principal, String share) {
    var recipient = recipients.requireById(principal.recipientId());
    return shares
        .find(share)
        .filter(candidate -> permissions.hasSelect(candidate, recipient))
        .orElseThrow(() -> ApiException.notFound("share '" + share + "' does not exist"));
  }

  private Page<TableResponse> listInSchema(ShareEntity share, String schema, Pageable pageable) {
    String schemaName = ObjectNames.normalize(schema);
    if (!objects.existsInSchema(share, schemaName)) {
      throw ApiException.notFound(
          "schema '" + schema + "' does not exist in share '" + share.getName() + "'");
    }
    return objects
        .findSchemaGrant(share, schemaName)
        .map(grant -> listChildren(share, grant, pageable))
        .orElseGet(
            () ->
                objects
                    .listTablesInSchema(share, schemaName, pageable)
                    .map(table -> fromStored(share, table)));
  }

  private ResolvedAsset resolveTable(ShareEntity share, String schema, String table) {
    String schemaName = ObjectNames.normalize(schema);
    String tableName = ObjectNames.normalize(table);
    if (!objects.existsInSchema(share, schemaName)) {
      throw ApiException.notFound(
          "schema '" + schema + "' does not exist in share '" + share.getName() + "'");
    }
    return objects
        .findSchemaGrant(share, schemaName)
        .map(grant -> findChild(share, grant, tableName))
        .orElseGet(
            () -> {
              SharedDataObjectEntity object =
                  objects
                      .findTable(share, schemaName, tableName)
                      .orElseThrow(() -> tableNotFound(share, schema, table));
              return catalog.resolveAsset(
                  AssetLookup.of(object.getType(), object.getName()), owner(share));
            });
  }

  private ResolvedAsset findChild(
      ShareEntity share, SharedDataObjectEntity grant, String tableName) {
    for (ResolvedAsset child :
        catalog.listChildren(
            AssetLookup.of(AssetType.SCHEMA, grant.getName()), owner(share))) {
      if (child.type() == AssetType.TABLE
          && tableName.equals(ObjectNames.normalize(lastSegment(child.identifier())))) {
        return child;
      }
    }
    throw tableNotFound(share, grant.getSharedAsSchema(), tableName);
  }

  private static Instant parseTimestamp(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException invalid) {
      throw ApiException.invalidParameter("timestamp must be an ISO8601 UTC timestamp");
    }
  }

  // limitHint counts unfiltered file records, so it is dropped when any predicate hint is present.
  private static Long limitHint(QueryTableRequest body) {
    boolean hasPredicate =
        (body.predicateHints() != null && !body.predicateHints().isEmpty())
            || (body.jsonPredicateHints() != null && !body.jsonPredicateHints().isBlank());
    return hasPredicate || body.limitHint() == null ? null : body.limitHint().longValue();
  }

  private static ApiException tableNotFound(ShareEntity share, String schema, String table) {
    return ApiException.notFound(
        "table '"
            + schema
            + "."
            + table
            + "' does not exist in share '"
            + share.getName()
            + "'");
  }

  private Page<TableResponse> listAll(ShareEntity share, Pageable pageable) {
    List<SharedDataObjectEntity> grants = objects.listSchemaGrants(share);
    if (grants.isEmpty()) {
      return objects.listTables(share, pageable).map(table -> fromStored(share, table));
    }
    Map<String, TableResponse> byAlias = new LinkedHashMap<>();
    for (SharedDataObjectEntity table : objects.listTables(share)) {
      TableResponse listed = fromStored(share, table);
      byAlias.put(alias(listed), listed);
    }
    for (SharedDataObjectEntity grant : grants) {
      addChildren(share, grant, byAlias);
    }
    List<TableResponse> tables = new ArrayList<>(byAlias.values());
    tables.sort(
        Comparator.comparing((TableResponse table) -> ObjectNames.normalize(table.schema()))
            .thenComparing(table -> ObjectNames.normalize(table.name())));
    return page(tables, pageable);
  }

  /** Catalog tables currently in the shared schema. */
  private Page<TableResponse> listChildren(
      ShareEntity share, SharedDataObjectEntity grant, Pageable pageable) {
    Map<String, TableResponse> byAlias = new LinkedHashMap<>();
    addChildren(share, grant, byAlias);
    List<TableResponse> tables = new ArrayList<>(byAlias.values());
    tables.sort(Comparator.comparing(table -> ObjectNames.normalize(table.name())));
    return page(tables, pageable);
  }

  private void addChildren(
      ShareEntity share, SharedDataObjectEntity grant, Map<String, TableResponse> byAlias) {
    for (ResolvedAsset child :
        catalog.listChildren(AssetLookup.of(AssetType.SCHEMA, grant.getName()), owner(share))) {
      if (child.type() != AssetType.TABLE) {
        continue;
      }
      TableResponse listed = fromChild(share, grant.getSharedAsSchema(), child);
      byAlias.putIfAbsent(alias(listed), listed);
    }
  }

  /** Slice by absolute offset; clamp if the token is past the end. hasNext is offset-based, not page-number. */
  private static Page<TableResponse> page(List<TableResponse> tables, Pageable pageable) {
    int from = Math.min(Math.toIntExact(pageable.getOffset()), tables.size());
    int to = Math.min(from + pageable.getPageSize(), tables.size());
    return new PageImpl<>(tables.subList(from, to), pageable, tables.size()) {
      @Override
      public boolean hasNext() {
        return pageable.getOffset() + getNumberOfElements() < getTotalElements();
      }
    };
  }

  private TableResponse fromStored(ShareEntity share, SharedDataObjectEntity object) {
    ResolvedAsset resolved =
        catalog.resolveAsset(AssetLookup.of(object.getType(), object.getName()), owner(share));
    return new TableResponse(
        object.getSharedAsTable(),
        object.getSharedAsSchema(),
        share.getName(),
        share.getId(),
        object.getSourceAssetId(),
        resolved.storageLocation(),
        emptyToNull(resolved.auxiliaryLocations()),
        TableAccessModes.forTable(resolved));
  }

  private static TableResponse fromChild(
      ShareEntity share, String schemaName, ResolvedAsset child) {
    return new TableResponse(
        lastSegment(child.identifier()),
        schemaName,
        share.getName(),
        share.getId(),
        child.catalogAssetId(),
        child.storageLocation(),
        emptyToNull(child.auxiliaryLocations()),
        TableAccessModes.forTable(child));
  }

  private static AuthContext owner(ShareEntity share) {
    return AuthContext.of(new UserContext(share.getOwnerId(), null));
  }

  private static String alias(TableResponse table) {
    return ObjectNames.normalize(table.schema()) + "." + ObjectNames.normalize(table.name());
  }

  private static String lastSegment(String identifier) {
    int dot = identifier.lastIndexOf('.');
    return dot < 0 ? identifier : identifier.substring(dot + 1);
  }

  private static List<String> emptyToNull(List<String> values) {
    return values == null || values.isEmpty() ? null : values;
  }
}
