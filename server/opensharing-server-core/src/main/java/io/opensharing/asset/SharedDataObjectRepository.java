package io.opensharing.asset;

import io.opensharing.catalog.AssetType;
import io.opensharing.share.ShareEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** JPA access to objects included in shares. */
public interface SharedDataObjectRepository
    extends JpaRepository<SharedDataObjectEntity, String> {

  boolean existsByShareAndName(ShareEntity share, String name);

  boolean existsByShareAndSharedAsSchemaAndSharedAsTable(
      ShareEntity share, String sharedAsSchema, String sharedAsTable);

  boolean existsByShareAndSharedAsSchemaAndTypeAndSharedAsTable(
      ShareEntity share, String sharedAsSchema, AssetType type, String sharedAsTable);

  boolean existsByShareAndSharedAsSchemaAndTypeAndSharedAsTableNot(
      ShareEntity share, String sharedAsSchema, AssetType type, String sharedAsTable);

  Optional<SharedDataObjectEntity> findByShareAndName(ShareEntity share, String name);

  Optional<SharedDataObjectEntity> findByShareAndSharedAsSchemaAndSharedAsTable(
      ShareEntity share, String sharedAsSchema, String sharedAsTable);

  List<SharedDataObjectEntity> findByShareOrderBySharedAsSchemaAscSharedAsTableAsc(
      ShareEntity share);

  @Query(
      value =
          "select distinct o.sharedAsSchema from SharedDataObjectEntity o "
              + "where o.share = :share order by o.sharedAsSchema asc",
      countQuery =
          "select count(distinct o.sharedAsSchema) from SharedDataObjectEntity o "
              + "where o.share = :share")
  Page<String> findDistinctSharedAsSchemasByShare(
      @Param("share") ShareEntity share, Pageable pageable);

  boolean existsByShareAndSharedAsSchema(ShareEntity share, String sharedAsSchema);

  Optional<SharedDataObjectEntity> findByShareAndSharedAsSchemaAndTypeAndSharedAsTable(
      ShareEntity share, String sharedAsSchema, AssetType type, String sharedAsTable);

  @Query(
      value =
          "select o from SharedDataObjectEntity o "
              + "where o.share = :share and o.sharedAsSchema = :schema "
              + "and o.type = :type and o.sharedAsTable <> '' "
              + "order by o.sharedAsTable asc",
      countQuery =
          "select count(o) from SharedDataObjectEntity o "
              + "where o.share = :share and o.sharedAsSchema = :schema "
              + "and o.type = :type and o.sharedAsTable <> ''")
  Page<SharedDataObjectEntity> findTablesInSchema(
      @Param("share") ShareEntity share,
      @Param("schema") String schema,
      @Param("type") AssetType type,
      Pageable pageable);

  List<SharedDataObjectEntity>
      findByShareAndSharedAsSchemaAndTypeAndSharedAsTableNotOrderBySharedAsTableAsc(
          ShareEntity share, String sharedAsSchema, AssetType type, String sharedAsTable);
}
