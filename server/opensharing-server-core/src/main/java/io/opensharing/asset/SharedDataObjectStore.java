package io.opensharing.asset;

import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.http.ApiException;
import io.opensharing.share.ShareEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists catalog objects included in a share. */
@Service
@Transactional
public class SharedDataObjectStore {

  private final SharedDataObjectRepository objects;

  public SharedDataObjectStore(SharedDataObjectRepository objects) {
    this.objects = objects;
  }

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
    object.setSourceSubtype(resolved.subtype());
    object.setSourceFormat(resolved.format());
    object.setSharedAsSchema(sharedAsSchema);
    object.setSharedAsTable(sharedAsTable);
    return objects.save(object);
  }

  @Transactional(readOnly = true)
  public List<SharedDataObjectEntity> list(ShareEntity share) {
    return objects.findByShareOrderBySharedAsSchemaAscSharedAsTableAsc(share);
  }

  @Transactional(readOnly = true)
  public Page<String> listSchemas(ShareEntity share, Pageable pageable) {
    return objects.findDistinctSharedAsSchemasByShare(share, pageable);
  }

  @Transactional(readOnly = true)
  public boolean existsInSchema(ShareEntity share, String schema) {
    return objects.existsByShareAndSharedAsSchema(share, schema);
  }

  @Transactional(readOnly = true)
  public Optional<SharedDataObjectEntity> findSchemaGrant(ShareEntity share, String schema) {
    return objects.findByShareAndSharedAsSchemaAndTypeAndSharedAsTable(
        share, schema, AssetType.SCHEMA, "");
  }

  @Transactional(readOnly = true)
  public Page<SharedDataObjectEntity> listTablesInSchema(
      ShareEntity share, String schema, Pageable pageable) {
    return objects.findTablesInSchema(share, schema, AssetType.TABLE, pageable);
  }

  @Transactional(readOnly = true)
  public List<SharedDataObjectEntity> listTablesInSchema(ShareEntity share, String schema) {
    return objects.findByShareAndSharedAsSchemaAndTypeAndSharedAsTableNotOrderBySharedAsTableAsc(
        share, schema, AssetType.TABLE, "");
  }

  @Transactional(readOnly = true)
  public Page<SharedDataObjectEntity> listTables(ShareEntity share, Pageable pageable) {
    return objects.findTables(share, AssetType.TABLE, pageable);
  }

  @Transactional(readOnly = true)
  public List<SharedDataObjectEntity> listTables(ShareEntity share) {
    return objects.findByShareAndTypeAndSharedAsTableNotOrderBySharedAsSchemaAscSharedAsTableAsc(
        share, AssetType.TABLE, "");
  }

  @Transactional(readOnly = true)
  public List<SharedDataObjectEntity> listSchemaGrants(ShareEntity share) {
    return objects.findByShareAndTypeAndSharedAsTableOrderBySharedAsSchemaAsc(
        share, AssetType.SCHEMA, "");
  }

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

  public void removeByName(ShareEntity share, AssetType type, String name) {
    SharedDataObjectEntity object =
        objects.findByShareAndName(share, name).orElseThrow(() -> notIncluded(share, name));
    requireType(object, type, share);
    objects.delete(object);
  }

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
