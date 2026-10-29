package io.opensharing.asset;

import io.opensharing.Transactions;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.TableProperties;
import io.opensharing.http.ApiException;
import io.opensharing.share.ShareEntity;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;

/**
 * Persists catalog objects included in a share. Callers resolve the object in the catalog and parse
 * its alias first; the store only enforces the share's alias rules. Adds and removes run in the
 * caller's transaction, so a batch of them commits or rolls back together.
 */
public class SharedDataObjectStore {

  private final Transactions tx;

  public SharedDataObjectStore(Transactions tx) {
    this.tx = tx;
  }

  /** Includes an object in the share. Fails when its alias or the object is already in it. */
  public SharedDataObjectEntity add(
      EntityManager em,
      ShareEntity share,
      String fullName,
      AssetType type,
      ResolvedAsset resolved,
      String sharedAsSchema,
      String sharedAsTable) {
    if (findByAlias(em, share, sharedAsSchema, sharedAsTable).isPresent()) {
      throw ApiException.alreadyExists(
          "alias '"
              + sharedAsSchema
              + "."
              + sharedAsTable
              + "' already exists in share '"
              + share.getName()
              + "'");
    }
    if (findByFullName(em, share, fullName).isPresent()) {
      throw ApiException.alreadyExists(
          "'" + fullName + "' is already included in share '" + share.getName() + "'");
    }

    SharedDataObjectEntity object = new SharedDataObjectEntity();
    object.setShare(em.getReference(ShareEntity.class, share.getId()));
    object.setSourceAssetId(resolved.catalogAssetId());
    object.setFullName(fullName);
    object.setType(type);
    if (resolved.additionalProperties() instanceof TableProperties table) {
      object.setSourceFormat(table.dataSourceFormat());
    }
    if (type == AssetType.TABLE && resolved.location() != null) {
      object.setStorageLocation(resolved.location().storageLocation());
      object.setAuxiliaryLocations(resolved.location().auxiliaryLocations());
    }
    object.setSharedAsSchema(sharedAsSchema);
    object.setSharedAsTable(sharedAsTable);
    em.persist(object);
    return object;
  }

  /** The objects in {@code share}, ordered by the schema and table they are shared as. */
  public List<SharedDataObjectEntity> list(ShareEntity share) {
    return tx.inTransaction(
        true,
        em ->
            em.createQuery(
                    "select o from SharedDataObjectEntity o where o.share.id = :shareId"
                        + " order by o.sharedAsSchema, o.sharedAsTable",
                    SharedDataObjectEntity.class)
                .setParameter("shareId", share.getId())
                .getResultList());
  }

  /** The objects {@code share} shares under {@code schema}, ordered by table. */
  public List<SharedDataObjectEntity> listInSchema(ShareEntity share, String schema) {
    return tx.inTransaction(
        true,
        em ->
            em.createQuery(
                    "select o from SharedDataObjectEntity o where o.share.id = :shareId"
                        + " and o.sharedAsSchema = :schema order by o.sharedAsTable",
                    SharedDataObjectEntity.class)
                .setParameter("shareId", share.getId())
                .setParameter("schema", schema)
                .getResultList());
  }

  /** The object {@code share} shares under the alias. */
  public Optional<SharedDataObjectEntity> findByAlias(
      ShareEntity share, String sharedAsSchema, String sharedAsTable) {
    return tx.inTransaction(true, em -> findByAlias(em, share, sharedAsSchema, sharedAsTable));
  }

  /** One page of the schemas recipients see in {@code share}, ordered by name. */
  public List<String> listSchemas(ShareEntity share, int offset, int limit) {
    return tx.inTransaction(
        true,
        em ->
            em.createQuery(
                    "select distinct o.sharedAsSchema from SharedDataObjectEntity o"
                        + " where o.share.id = :shareId order by o.sharedAsSchema",
                    String.class)
                .setParameter("shareId", share.getId())
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList());
  }

  /** Removes the object shared under the alias. Fails unless it exists with the given type. */
  public void removeByAlias(
      EntityManager em,
      ShareEntity share,
      AssetType type,
      String sharedAsSchema,
      String sharedAsTable) {
    SharedDataObjectEntity object =
        findByAlias(em, share, sharedAsSchema, sharedAsTable)
            .orElseThrow(() -> notIncluded(share, sharedAsSchema + "." + sharedAsTable));
    requireType(object, type, share);
    em.remove(object);
  }

  /** Removes the object by full catalog name. Fails unless it exists with the given type. */
  public void removeByFullName(
      EntityManager em, ShareEntity share, AssetType type, String fullName) {
    SharedDataObjectEntity object =
        findByFullName(em, share, fullName).orElseThrow(() -> notIncluded(share, fullName));
    requireType(object, type, share);
    em.remove(object);
  }

  private static Optional<SharedDataObjectEntity> findByFullName(
      EntityManager em, ShareEntity share, String fullName) {
    return em.createQuery(
            "select o from SharedDataObjectEntity o where o.share.id = :shareId"
                + " and o.fullName = :fullName",
            SharedDataObjectEntity.class)
        .setParameter("shareId", share.getId())
        .setParameter("fullName", fullName)
        .getResultStream()
        .findFirst();
  }

  private static Optional<SharedDataObjectEntity> findByAlias(
      EntityManager em, ShareEntity share, String sharedAsSchema, String sharedAsTable) {
    return em.createQuery(
            "select o from SharedDataObjectEntity o where o.share.id = :shareId"
                + " and o.sharedAsSchema = :schema and o.sharedAsTable = :table",
            SharedDataObjectEntity.class)
        .setParameter("shareId", share.getId())
        .setParameter("schema", sharedAsSchema)
        .setParameter("table", sharedAsTable)
        .getResultStream()
        .findFirst();
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
