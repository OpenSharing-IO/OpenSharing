package io.opensharing.asset.table;

import io.opensharing.ObjectNames;
import io.opensharing.asset.IncludedAssets;
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
import io.opensharing.catalog.TableProperties;
import io.opensharing.exception.AssetNotFoundException;
import io.opensharing.http.ApiException;
import io.opensharing.http.ListResponse;
import io.opensharing.http.Pagination;
import io.opensharing.recipient.RecipientPrincipal;
import io.opensharing.share.ShareEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The tables a recipient sees through the sharing protocol in a share it holds SELECT on: the
 * tables the share includes one by one, plus the tables the catalog currently lists under each
 * schema the share includes as a whole.
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
  private final ProtocolShares protocolShares;
  private final SharedDataObjectStore sharedObjects;

  public ProtocolTables(
      CatalogConnector catalog,
      ProtocolShares protocolShares,
      SharedDataObjectStore sharedObjects) {
    this.catalog = catalog;
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
    List<ListedTable> listedTables = listTables(share, objectsInSchema(share, schemaAlias));
    return page(share, listedTables, maxResults, pageToken);
  }

  /** Lists one page of all the tables in the share, by schema alias and then table alias. */
  public ListResponse<TableResponse> listAll(
      RecipientPrincipal recipient, String shareName, Integer maxResults, String pageToken) {
    ShareEntity share = protocolShares.requireGranted(recipient, shareName);
    List<ListedTable> listedTables = listTables(share, sharedObjects.list(share));
    return page(share, listedTables, maxResults, pageToken);
  }

  /** Gets one table of the share by its schema alias and table alias. */
  public GetTableResponse get(
      RecipientPrincipal recipient, String shareName, String schemaAlias, String tableAlias) {
    ShareEntity share = protocolShares.requireGranted(recipient, shareName);
    ListedTable table = findTable(share, schemaAlias, tableAlias);
    return new GetTableResponse(
        table.tableAlias(),
        table.schemaAlias(),
        share.getName(),
        share.getId(),
        table.tableId(),
        format(table.catalogTable().get()));
  }

  /** The objects the share includes under {@code schemaAlias}. */
  private List<SharedDataObjectEntity> objectsInSchema(ShareEntity share, String schemaAlias) {
    List<SharedDataObjectEntity> schemaObjects = sharedObjects.listInSchema(share, schemaAlias);
    if (schemaObjects.isEmpty()) {
      throw ApiException.notFound(
          "schema '" + schemaAlias + "' does not exist in share '" + share.getName() + "'");
    }
    return schemaObjects;
  }

  /**
   * The table under {@code schemaAlias} and {@code tableAlias}: an included table, or a table the
   * catalog holds directly in an included schema, resolved without listing the schema. A name with
   * a dot is never on the listing, and a hidden table, or any table under a hidden schema, is
   * missing.
   */
  private ListedTable findTable(ShareEntity share, String schemaAlias, String tableAlias) {
    for (SharedDataObjectEntity sharedObject : objectsInSchema(share, schemaAlias)) {
      if (sharedObject.getType() == AssetType.TABLE
          && tableAlias.equals(sharedObject.getSharedAsTable())) {
        ResolvedAsset catalogTable = resolveIncluded(share, sharedObject);
        if (catalogTable == null) {
          break;
        }
        return new ListedTable(
            sharedObject.getSharedAsSchema(),
            sharedObject.getSharedAsTable(),
            sharedObject.getSourceAssetId(),
            () -> catalogTable);
      }
      if (sharedObject.getType() == AssetType.SCHEMA && !tableAlias.contains(".")) {
        if (resolveIncluded(share, sharedObject) == null) {
          break;
        }
        Asset childTable =
            new Asset(AssetType.TABLE, sharedObject.getFullName() + "." + tableAlias);
        try {
          ResolvedAsset catalogTable = catalog.resolveAsset(childTable, owner(share));
          return new ListedTable(
              sharedObject.getSharedAsSchema(),
              alias(catalogTable),
              catalogTable.catalogAssetId(),
              () -> catalogTable);
        } catch (AssetNotFoundException notInCatalog) {
          break;
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

  /**
   * A table on the listing. Included tables are resolved only when they land on the requested
   * page, to null when hidden; catalog children arrive resolved.
   */
  private record ListedTable(
      String schemaAlias,
      String tableAlias,
      String tableId,
      Supplier<ResolvedAsset> catalogTable) {}

  /**
   * The tables {@code includedObjects} puts on the listing, by schema alias and then table alias:
   * each included table, plus the catalog's current tables under each included schema.
   */
  private List<ListedTable> listTables(
      ShareEntity share, List<SharedDataObjectEntity> includedObjects) {
    List<ListedTable> listedTables = new ArrayList<>();
    for (SharedDataObjectEntity sharedObject : includedObjects) {
      String schemaAlias = sharedObject.getSharedAsSchema();
      if (sharedObject.getType() == AssetType.SCHEMA) {
        if (resolveIncluded(share, sharedObject) == null) {
          continue;
        }
        for (ResolvedAsset catalogTable : catalogTablesIn(share, sharedObject)) {
          listedTables.add(
              new ListedTable(
                  schemaAlias,
                  alias(catalogTable),
                  catalogTable.catalogAssetId(),
                  () -> catalogTable));
        }
      } else if (sharedObject.getType() == AssetType.TABLE) {
        listedTables.add(includedTable(share, sharedObject));
      }
    }
    listedTables.sort(
        Comparator.comparing(ListedTable::schemaAlias).thenComparing(ListedTable::tableAlias));
    return listedTables;
  }

  /** The included {@code sharedTable}, resolved only when read. */
  private ListedTable includedTable(ShareEntity share, SharedDataObjectEntity sharedTable) {
    return new ListedTable(
        sharedTable.getSharedAsSchema(),
        sharedTable.getSharedAsTable(),
        sharedTable.getSourceAssetId(),
        () -> resolveIncluded(share, sharedTable));
  }

  /**
   * The catalog table or schema behind the included {@code sharedObject}, or null once the catalog
   * has dropped it or replaced it with another of the same name. That hides it, and every table
   * under a hidden schema, from recipients until the provider shares it again.
   */
  private ResolvedAsset resolveIncluded(ShareEntity share, SharedDataObjectEntity sharedObject) {
    Optional<ResolvedAsset> catalogAsset =
        IncludedAssets.resolve(catalog, sharedObject, owner(share));
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

  /**
   * One page of {@code listedTables}, resolving only the tables on it and leaving out hidden ones.
   */
  private ListResponse<TableResponse> page(
      ShareEntity share, List<ListedTable> listedTables, Integer maxResults, String pageToken) {
    ListResponse<TableResponse> tablePage =
        Pagination.protocolPage(
            maxResults,
            pageToken,
            (offset, limit) -> {
              int fromIndex = Math.min(offset, listedTables.size());
              int toIndex = fromIndex + Math.min(limit, listedTables.size() - fromIndex);
              return listedTables.subList(fromIndex, toIndex);
            },
            listedTable -> response(share, listedTable));
    return new ListResponse<>(
        tablePage.items().stream().filter(Objects::nonNull).toList(), tablePage.nextPageToken());
  }

  /** Every table the catalog lists under the included {@code sharedSchema}, across all pages. */
  private List<ResolvedAsset> catalogTablesIn(
      ShareEntity share, SharedDataObjectEntity sharedSchema) {
    Asset catalogSchema = new Asset(AssetType.SCHEMA, sharedSchema.getFullName());
    List<ResolvedAsset> catalogTables = new ArrayList<>();
    String catalogPageToken = null;
    do {
      AssetPage catalogPage =
          catalog.listChildren(catalogSchema, CATALOG_PAGE_SIZE, catalogPageToken, owner(share));
      catalogPage.assets().stream()
          .filter(child -> child.type() == AssetType.TABLE)
          .forEach(catalogTables::add);
      catalogPageToken = catalogPage.nextPageToken();
    } while (catalogPageToken != null);
    return catalogTables;
  }

  /**
   * The listed table with the storage locations and access modes of its catalog table, or null when
   * it is hidden.
   */
  private static TableResponse response(ShareEntity share, ListedTable listedTable) {
    ResolvedAsset catalogTable = listedTable.catalogTable().get();
    if (catalogTable == null) {
      return null;
    }
    AssetLocation location = catalogTable.location();
    return new TableResponse(
        listedTable.tableAlias(),
        listedTable.schemaAlias(),
        share.getName(),
        share.getId(),
        listedTable.tableId(),
        location == null ? null : location.storageLocation(),
        location == null || location.auxiliaryLocations().isEmpty()
            ? null
            : location.auxiliaryLocations(),
        TableAccessModes.forTable(catalogTable));
  }

  /** The table's format by its protocol name, or null when the catalog reports none. */
  private static String format(ResolvedAsset catalogTable) {
    return catalogTable.additionalProperties() instanceof TableProperties tableProperties
            && tableProperties.dataSourceFormat() != null
        ? tableProperties.dataSourceFormat().wireName()
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
