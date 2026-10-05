package io.opensharing.asset;

import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.TableProperties;
import io.opensharing.http.ApiException;
import io.opensharing.share.ShareEntity;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists catalog objects included in a share. Callers resolve the object in the catalog and parse
 * its alias first; the store only enforces the share's alias rules.
 */
@Service
@Transactional
public class SharedDataObjectStore {

  private final SharedDataObjectRepository objects;

  public SharedDataObjectStore(SharedDataObjectRepository objects) {
    this.objects = objects;
  }

  /**
   * Includes an object in the share. Fails when the alias or the object is already in the share,
   * when a table is added under an already included schema, or when a schema is added while tables
   * under it are already included.
   */
  public SharedDataObjectEntity add(
      ShareEntity share,
      String name,
      AssetType type,
      ResolvedAsset resolved,
      String sharedAsSchema,
      String sharedAsTable) {
    if (objects.existsByShareAndSharedAsSchemaAndSharedAsTable(
        share, sharedAsSchema, sharedAsTable)) {
      String alias =
          sharedAsTable.isEmpty() ? sharedAsSchema : sharedAsSchema + "." + sharedAsTable;
      throw ApiException.alreadyExists(
          "alias '" + alias + "' already exists in share '" + share.getName() + "'");
    }
    if (objects.existsByShareAndName(share, name)) {
      throw ApiException.alreadyExists(
          "'" + name + "' is already included in share '" + share.getName() + "'");
    }
    if (!sharedAsTable.isEmpty()
        && objects.existsByShareAndSharedAsSchemaAndTypeAndSharedAsTable(
            share, sharedAsSchema, AssetType.SCHEMA, "")) {
      throw ApiException.conflict(
          "schema '"
              + sharedAsSchema
              + "' is already included in share '"
              + share.getName()
              + "'");
    }
    if (sharedAsTable.isEmpty()
        && objects.existsByShareAndSharedAsSchemaAndTypeAndSharedAsTableNot(
            share, sharedAsSchema, AssetType.TABLE, "")) {
      throw ApiException.conflict(
          "tables under schema '"
              + sharedAsSchema
              + "' are already included in share '"
              + share.getName()
              + "'");
    }

    SharedDataObjectEntity object = new SharedDataObjectEntity();
    object.setShare(share);
    object.setSourceAssetId(resolved.catalogAssetId());
    object.setName(name);
    object.setType(type);
    if (resolved.additionalProperties() instanceof TableProperties table) {
      object.setSourceFormat(table.dataSourceFormat());
    }
    object.setSharedAsSchema(sharedAsSchema);
    object.setSharedAsTable(sharedAsTable);
    return objects.save(object);
  }

  /** Lists the share's objects ordered by alias. */
  @Transactional(readOnly = true)
  public List<SharedDataObjectEntity> list(ShareEntity share) {
    return objects.findByShareOrderBySharedAsSchemaAscSharedAsTableAsc(share);
  }

  /** Removes the object shared under the alias. Fails unless it exists with the given type. */
  public void removeByAlias(
      ShareEntity share, AssetType type, String sharedAsSchema, String sharedAsTable) {
    String alias = sharedAsTable.isEmpty() ? sharedAsSchema : sharedAsSchema + "." + sharedAsTable;
    SharedDataObjectEntity object =
        objects
            .findByShareAndSharedAsSchemaAndSharedAsTable(share, sharedAsSchema, sharedAsTable)
            .orElseThrow(() -> notIncluded(share, alias));
    requireType(object, type, share);
    objects.delete(object);
  }

  /** Removes the object by catalog name. Fails unless it exists with the given type. */
  public void removeByName(ShareEntity share, AssetType type, String name) {
    SharedDataObjectEntity object =
        objects.findByShareAndName(share, name).orElseThrow(() -> notIncluded(share, name));
    requireType(object, type, share);
    objects.delete(object);
  }

  // An object of another type under the same name or alias counts as not included.
  private static void requireType(
      SharedDataObjectEntity object, AssetType type, ShareEntity share) {
    if (object.getType() != type) {
      throw notIncluded(share, object.getSharedAs());
    }
  }

  private static ApiException notIncluded(ShareEntity share, String object) {
    return ApiException.notFound(
        "'" + object + "' is not included in share '" + share.getName() + "'");
  }
}
