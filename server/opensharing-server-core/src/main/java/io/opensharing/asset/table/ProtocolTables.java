package io.opensharing.asset.table;

import io.opensharing.ObjectNames;
import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.asset.SharedDataObjectStore;
import io.opensharing.asset.share.ProtocolShares;
import io.opensharing.auth.AuthContext;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.Asset;
import io.opensharing.catalog.AssetLocation;
import io.opensharing.catalog.AssetPage;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.http.ApiException;
import io.opensharing.http.ListResponse;
import io.opensharing.http.Pagination;
import io.opensharing.recipient.RecipientPrincipal;
import io.opensharing.share.ShareEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * The tables a recipient sees through the sharing protocol in a share it holds SELECT on: the
 * tables the share includes one by one, plus the tables the catalog currently lists under each
 * schema the share includes as a whole.
 *
 * <p>Every catalog call is made as the share's owner, so the catalog decides whether the owner may
 * still read each table.
 */
public class ProtocolTables {

  private static final int CATALOG_PAGE_SIZE = 1000;

  private final CatalogConnector catalog;
  private final ProtocolShares shares;
  private final SharedDataObjectStore objects;

  public ProtocolTables(
      CatalogConnector catalog, ProtocolShares shares, SharedDataObjectStore objects) {
    this.catalog = catalog;
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
    List<SharedDataObjectEntity> included =
        objects.listInSchema(granted, ObjectNames.normalize(schema));
    if (included.isEmpty()) {
      throw ApiException.notFound(
          "schema '" + schema + "' does not exist in share '" + granted.getName() + "'");
    }
    return page(granted, listed(granted, included), maxResults, pageToken);
  }

  /** Lists one page of all the tables in the share, by schema and then name. */
  public ListResponse<TableResponse> listAll(
      RecipientPrincipal recipient, String share, Integer maxResults, String pageToken) {
    ShareEntity granted = shares.requireGranted(recipient, share);
    return page(granted, listed(granted, objects.list(granted)), maxResults, pageToken);
  }

  /**
   * A table on the listing. Included tables are resolved only when they land on the requested
   * page; catalog children arrive resolved.
   */
  private record Listed(String schema, String name, String id, Supplier<ResolvedAsset> resolved) {}

  /**
   * The tables {@code included} puts on the listing, by schema alias and then table alias: each
   * included table, plus the catalog's current tables under each included schema.
   */
  private List<Listed> listed(ShareEntity share, List<SharedDataObjectEntity> included) {
    List<Listed> tables = new ArrayList<>();
    for (SharedDataObjectEntity object : included) {
      String schema = object.getSharedAsSchema();
      if (object.getType() == AssetType.SCHEMA) {
        for (ResolvedAsset child : tablesIn(share, object)) {
          tables.add(new Listed(schema, alias(child), child.catalogAssetId(), () -> child));
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
    tables.sort(Comparator.comparing(Listed::schema).thenComparing(Listed::name));
    return tables;
  }

  /** One page of {@code tables}, resolving only the tables on it. */
  private ListResponse<TableResponse> page(
      ShareEntity share, List<Listed> tables, Integer maxResults, String pageToken) {
    return Pagination.protocolPage(
        maxResults,
        pageToken,
        (offset, limit) -> {
          int from = Math.min(offset, tables.size());
          return tables.subList(from, from + Math.min(limit, tables.size() - from));
        },
        table -> response(share, table));
  }

  /** Every table the catalog lists under the included {@code schema}, across all its pages. */
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

  /** The listed table with the storage locations and access modes of its catalog table. */
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

  /** The share's owner, whom every catalog call acts as. */
  private static AuthContext owner(ShareEntity share) {
    return AuthContext.of(UserContext.fromUserIdAndName(share.getOwnerId(), null));
  }

  /**
   * The alias recipients see for a catalog table in an included schema: its table name, lowercased
   * like every alias.
   */
  private static String alias(ResolvedAsset table) {
    String fullName = table.fullName();
    return ObjectNames.normalize(fullName.substring(fullName.lastIndexOf('.') + 1));
  }
}
