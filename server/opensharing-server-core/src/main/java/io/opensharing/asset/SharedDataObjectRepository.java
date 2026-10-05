package io.opensharing.asset;

import io.opensharing.catalog.AssetType;
import io.opensharing.share.ShareEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * JPA access to objects included in shares. A schema-level row has an empty {@code
 * shared_as_table}.
 */
public interface SharedDataObjectRepository
    extends JpaRepository<SharedDataObjectEntity, String> {

  /** {@code SELECT 1 FROM os_shared_data_objects WHERE share_id = ? AND name = ? LIMIT 1} */
  boolean existsByShareAndName(ShareEntity share, String name);

  /**
   * {@code SELECT 1 FROM os_shared_data_objects WHERE share_id = ? AND shared_as_schema = ? AND
   * shared_as_table = ? LIMIT 1}
   */
  boolean existsByShareAndSharedAsSchemaAndSharedAsTable(
      ShareEntity share, String sharedAsSchema, String sharedAsTable);

  /**
   * {@code SELECT 1 FROM os_shared_data_objects WHERE share_id = ? AND shared_as_schema = ? AND
   * type = ? AND shared_as_table = ? LIMIT 1}
   */
  boolean existsByShareAndSharedAsSchemaAndTypeAndSharedAsTable(
      ShareEntity share, String sharedAsSchema, AssetType type, String sharedAsTable);

  /**
   * {@code SELECT 1 FROM os_shared_data_objects WHERE share_id = ? AND shared_as_schema = ? AND
   * type = ? AND shared_as_table <> ? LIMIT 1}
   */
  boolean existsByShareAndSharedAsSchemaAndTypeAndSharedAsTableNot(
      ShareEntity share, String sharedAsSchema, AssetType type, String sharedAsTable);

  /** {@code SELECT * FROM os_shared_data_objects WHERE share_id = ? AND name = ?} */
  Optional<SharedDataObjectEntity> findByShareAndName(ShareEntity share, String name);

  /**
   * {@code SELECT * FROM os_shared_data_objects WHERE share_id = ? AND shared_as_schema = ? AND
   * shared_as_table = ?}
   */
  Optional<SharedDataObjectEntity> findByShareAndSharedAsSchemaAndSharedAsTable(
      ShareEntity share, String sharedAsSchema, String sharedAsTable);

  /**
   * {@code SELECT * FROM os_shared_data_objects WHERE share_id = ? ORDER BY shared_as_schema,
   * shared_as_table}
   */
  List<SharedDataObjectEntity> findByShareOrderBySharedAsSchemaAscSharedAsTableAsc(
      ShareEntity share);
}
