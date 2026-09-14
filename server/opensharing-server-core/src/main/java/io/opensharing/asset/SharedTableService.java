package io.opensharing.asset;

import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.http.ApiException;
import io.opensharing.http.OffsetPage;
import io.opensharing.share.ShareEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tables a recipient can reach in a share, whether shared one by one or by the schema that holds
 * them.
 *
 * <p>A share can hold both, so this is where the two are put together. Tables shared in their own
 * right are rows and are read from the store; a shared schema's tables are whatever the catalog says
 * the schema holds at the moment of asking, and are assembled per request. Everything downstream is
 * handed the same kind of object either way.
 *
 * <p>Expansion costs a catalog call, and merging two sources costs holding a schema's tables in
 * memory to page over them, so a share with no schema grant keeps its cheaper path: the query it
 * always used, paged by the database.
 */
@Service
@Transactional(readOnly = true)
public class SharedTableService {

  private final SharedDataObjectStore objects;
  private final AssetResolutionService resolution;

  public SharedTableService(SharedDataObjectStore objects, AssetResolutionService resolution) {
    this.objects = objects;
    this.resolution = resolution;
  }

  /**
   * One table a recipient named. A stored object answers directly; otherwise the schema it was asked
   * under may be shared as a whole, in which case the table is looked for among the schema's current
   * contents.
   *
   * <p>The catalog is asked what the schema holds rather than the name being assembled from the
   * schema's own, so that what can be read is exactly what was listed: a catalog that names its
   * children some other way, or that has stopped offering one, is answered correctly either way.
   */
  public SharedDataObjectEntity require(ShareEntity share, String schemaName, String tableName) {
    Optional<SharedDataObjectEntity> stored =
        objects.findActive(share, schemaName, tableName, AssetType.TABLE);
    if (stored.isPresent()) {
      return stored.get();
    }
    return objects
        .findActiveSchemaGrant(share, schemaName)
        .flatMap(grant -> expand(grant).stream().filter(table -> named(table, tableName)).findFirst())
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "table '"
                        + share.getName()
                        + "."
                        + schemaName
                        + "."
                        + tableName
                        + "' does not exist"));
  }

  /** The tables of one schema, whether shared individually, by the schema, or both. */
  public Page<SharedDataObjectEntity> listInSchema(
      ShareEntity share, String schemaName, Pageable pageable) {
    Optional<SharedDataObjectEntity> grant = objects.findActiveSchemaGrant(share, schemaName);
    if (grant.isEmpty()) {
      return objects.list(share, schemaName, AssetType.TABLE, pageable);
    }
    List<SharedDataObjectEntity> tables =
        merge(objects.listTablesInSchema(share, schemaName), expand(grant.get()));
    tables.sort(Comparator.comparing(SharedDataObjectEntity::getSharedAsNameLower));
    return OffsetPage.of(tables, pageable);
  }

  /** Every table in the share, across its schemas, for {@code GET /shares/{share}/all-tables}. */
  public Page<SharedDataObjectEntity> listAll(ShareEntity share, Pageable pageable) {
    List<SharedDataObjectEntity> grants = objects.listActiveSchemaGrants(share);
    if (grants.isEmpty()) {
      return objects.list(share, AssetType.TABLE, pageable);
    }
    List<SharedDataObjectEntity> expanded = new ArrayList<>();
    grants.forEach(grant -> expanded.addAll(expand(grant)));
    List<SharedDataObjectEntity> tables = merge(objects.listTables(share), expanded);
    tables.sort(
        Comparator.comparing(SharedDataObjectEntity::getSharedAsSchemaLower)
            .thenComparing(SharedDataObjectEntity::getSharedAsNameLower));
    return OffsetPage.of(tables, pageable);
  }

  /**
   * The tables a shared schema currently holds, each in the form the rest of the server works in.
   *
   * <p>A table keeps the name the catalog gives it, so a recipient sees the schema as the provider's
   * catalog has it. That is the bargain of sharing a schema rather than its tables: the provider gives
   * up naming them.
   */
  private List<SharedDataObjectEntity> expand(SharedDataObjectEntity schemaGrant) {
    List<SharedDataObjectEntity> tables = new ArrayList<>();
    for (ResolvedAsset child : resolution.listSharedSchemaTables(schemaGrant)) {
      SharedDataObjectEntity table =
          SharedDataObjectEntity.inSharedSchema(schemaGrant, child, lastLevelOf(child.identifier()));
      resolution.applySnapshot(table, child);
      tables.add(table);
    }
    return tables;
  }

  /**
   * A table shared in its own right wins over the same name from a shared schema. The provider named
   * that one deliberately, and it may well point somewhere else entirely — which is the whole reason
   * to add it alongside a shared schema.
   */
  private static List<SharedDataObjectEntity> merge(
      List<SharedDataObjectEntity> stored, List<SharedDataObjectEntity> expanded) {
    Map<String, SharedDataObjectEntity> byAlias = new LinkedHashMap<>();
    for (SharedDataObjectEntity table : stored) {
      byAlias.put(alias(table), table);
    }
    for (SharedDataObjectEntity table : expanded) {
      byAlias.putIfAbsent(alias(table), table);
    }
    return new ArrayList<>(byAlias.values());
  }

  private static String alias(SharedDataObjectEntity table) {
    return table.getSharedAsSchemaLower() + "." + table.getSharedAsNameLower();
  }

  private static boolean named(SharedDataObjectEntity table, String tableName) {
    return table.getSharedAsName().equalsIgnoreCase(tableName);
  }

  private static String lastLevelOf(String identifier) {
    return identifier.substring(identifier.lastIndexOf('.') + 1);
  }
}
