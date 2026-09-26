package io.opensharing.asset.table;

import io.opensharing.ObjectNames;
import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.asset.SharedDataObjectStore;
import io.opensharing.auth.AuthContext;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.AssetLookup;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.TableFormat;
import io.opensharing.http.ApiException;
import io.opensharing.http.ListResponse;
import io.opensharing.http.Listings;
import io.opensharing.recipient.RecipientPrincipal;
import io.opensharing.recipient.RecipientStore;
import io.opensharing.share.ShareEntity;
import io.opensharing.share.SharePermissionStore;
import io.opensharing.share.ShareStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Recipient protocol API for listing tables in a SELECT-granted share. */
@RestController
@RequestMapping(
    value = "${opensharing.protocol-prefix}/shares/{share}",
    produces = "application/json;charset=UTF-8")
public class ProtocolTableController {

  private final RecipientStore recipients;
  private final ShareStore shares;
  private final SharePermissionStore permissions;
  private final SharedDataObjectStore objects;
  private final CatalogConnector catalog;
  private final Listings listings;

  public ProtocolTableController(
      RecipientStore recipients,
      ShareStore shares,
      SharePermissionStore permissions,
      SharedDataObjectStore objects,
      CatalogConnector catalog,
      Listings listings) {
    this.recipients = recipients;
    this.shares = shares;
    this.permissions = permissions;
    this.objects = objects;
    this.catalog = catalog;
    this.listings = listings;
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
        accessModes(resolved));
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
        accessModes(child));
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

  /** Managed and external Delta tables support QueryTable (url) and temporary credentials (dir). */
  private static List<String> accessModes(ResolvedAsset asset) {
    if (asset.format() != TableFormat.DELTA) {
      return null;
    }
    if ("MANAGED".equalsIgnoreCase(asset.subtype())
        || "EXTERNAL".equalsIgnoreCase(asset.subtype())) {
      return List.of("url", "dir");
    }
    return null;
  }
}
