package io.opensharing.asset;

import io.opensharing.Transactions;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.TableProperties;
import io.opensharing.http.ApiException;
import io.opensharing.share.ShareEntity;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Persists catalog objects included in a share. Callers resolve the object in the catalog and parse
 * its alias first; the store only enforces the share's rules on aliases and catalog names. Adds and
 * removes run in the caller's transaction, so a batch of them commits or rolls back together. A
 * schema-level row has an empty {@code shared_as_table}.
 */
public class SharedDataObjectStore {

  private final Transactions tx;

  public SharedDataObjectStore(Transactions tx) {
    this.tx = tx;
  }

  /**
   * Includes an object in the share. Fails when the alias or the object is already in the share,
   * when a table is added under an already included schema, or when a schema is added while tables
   * under it are already included. Under means under the alias or in the catalog, so that no table
   * is shared twice under two aliases.
   */
  public SharedDataObjectEntity add(
      EntityManager em,
      ShareEntity share,
      String name,
      AssetType type,
      ResolvedAsset resolved,
      String sharedAsSchema,
      String sharedAsTable) {
    if (findByAlias(em, share, sharedAsSchema, sharedAsTable).isPresent()) {
      String alias =
          sharedAsTable.isEmpty() ? sharedAsSchema : sharedAsSchema + "." + sharedAsTable;
      throw ApiException.alreadyExists(
          "alias '" + alias + "' already exists in share '" + share.getName() + "'");
    }
    if (findByName(em, share, name).isPresent()) {
      throw ApiException.alreadyExists(
          "'" + name + "' is already included in share '" + share.getName() + "'");
    }
    if (!sharedAsTable.isEmpty() && schemaIncluded(em, share, sharedAsSchema)) {
      throw ApiException.conflict(
          "schema '" + sharedAsSchema + "' is already included in share '" + share.getName() + "'");
    }
    if (sharedAsTable.isEmpty() && tablesIncluded(em, share, sharedAsSchema)) {
      throw ApiException.conflict(
          "tables under schema '"
              + sharedAsSchema
              + "' are already included in share '"
              + share.getName()
              + "'");
    }
    Optional<SharedDataObjectEntity> overlapping =
        sharedAsTable.isEmpty() ? tableIn(em, share, name) : schemaOf(em, share, name);
    if (overlapping.isPresent()) {
      throw ApiException.conflict(
          "'"
              + overlapping.get().getName()
              + "' is already included in share '"
              + share.getName()
              + "' as '"
              + overlapping.get().getSharedAs()
              + "'");
    }

    SharedDataObjectEntity object = new SharedDataObjectEntity();
    object.setShare(em.getReference(ShareEntity.class, share.getId()));
    object.setSourceAssetId(resolved.catalogAssetId());
    object.setName(name);
    object.setType(type);
    if (resolved.additionalProperties() instanceof TableProperties table) {
      object.setSourceFormat(table.dataSourceFormat());
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

  /** Removes the object shared under the alias. Fails unless it exists with the given type. */
  public void removeByAlias(
      EntityManager em,
      ShareEntity share,
      AssetType type,
      String sharedAsSchema,
      String sharedAsTable) {
    String alias = sharedAsTable.isEmpty() ? sharedAsSchema : sharedAsSchema + "." + sharedAsTable;
    SharedDataObjectEntity object =
        findByAlias(em, share, sharedAsSchema, sharedAsTable)
            .orElseThrow(() -> notIncluded(share, alias));
    requireType(object, type, share);
    em.remove(object);
  }

  /** Removes the object by catalog name. Fails unless it exists with the given type. */
  public void removeByName(EntityManager em, ShareEntity share, AssetType type, String name) {
    SharedDataObjectEntity object =
        findByName(em, share, name).orElseThrow(() -> notIncluded(share, name));
    requireType(object, type, share);
    em.remove(object);
  }

  private static Optional<SharedDataObjectEntity> findByName(
      EntityManager em, ShareEntity share, String name) {
    return em.createQuery(
            "select o from SharedDataObjectEntity o where o.share.id = :shareId and o.name = :name",
            SharedDataObjectEntity.class)
        .setParameter("shareId", share.getId())
        .setParameter("name", name)
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

  /** Whether {@code share} includes all of {@code schema}. */
  private static boolean schemaIncluded(EntityManager em, ShareEntity share, String schema) {
    return !em.createQuery(
            "select o.id from SharedDataObjectEntity o where o.share.id = :shareId"
                + " and o.sharedAsSchema = :schema and o.type = :type and o.sharedAsTable = ''",
            String.class)
        .setParameter("shareId", share.getId())
        .setParameter("schema", schema)
        .setParameter("type", AssetType.SCHEMA)
        .setMaxResults(1)
        .getResultList()
        .isEmpty();
  }

  /** Whether {@code share} includes any single table of {@code schema}. */
  private static boolean tablesIncluded(EntityManager em, ShareEntity share, String schema) {
    return !em.createQuery(
            "select o.id from SharedDataObjectEntity o where o.share.id = :shareId"
                + " and o.sharedAsSchema = :schema and o.type = :type and o.sharedAsTable <> ''",
            String.class)
        .setParameter("shareId", share.getId())
        .setParameter("schema", schema)
        .setParameter("type", AssetType.TABLE)
        .setMaxResults(1)
        .getResultList()
        .isEmpty();
  }

  /** The included schema that holds the catalog table {@code table}. */
  private static Optional<SharedDataObjectEntity> schemaOf(
      EntityManager em, ShareEntity share, String table) {
    String schema = table.substring(0, table.lastIndexOf('.')).toLowerCase(Locale.ROOT);
    return em.createQuery(
            "select o from SharedDataObjectEntity o where o.share.id = :shareId"
                + " and o.type = :type and lower(o.name) = :schema",
            SharedDataObjectEntity.class)
        .setParameter("shareId", share.getId())
        .setParameter("type", AssetType.SCHEMA)
        .setParameter("schema", schema)
        .setMaxResults(1)
        .getResultStream()
        .findFirst();
  }

  /** An included table of the catalog schema {@code schema}. */
  private static Optional<SharedDataObjectEntity> tableIn(
      EntityManager em, ShareEntity share, String schema) {
    String prefix = schema.toLowerCase(Locale.ROOT) + ".";
    return em.createQuery(
            "select o from SharedDataObjectEntity o where o.share.id = :shareId"
                + " and o.type = :type and substring(lower(o.name), 1, :length) = :prefix",
            SharedDataObjectEntity.class)
        .setParameter("shareId", share.getId())
        .setParameter("type", AssetType.TABLE)
        .setParameter("length", prefix.length())
        .setParameter("prefix", prefix)
        .setMaxResults(1)
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
