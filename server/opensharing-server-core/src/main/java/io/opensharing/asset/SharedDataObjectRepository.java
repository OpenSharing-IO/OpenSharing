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

/**
 * Queries against shared objects. Recipient-facing lookups are filtered by status, so an object whose
 * source has gone away stops being served while staying visible to the provider admin.
 */
public interface SharedDataObjectRepository extends JpaRepository<SharedDataObjectEntity, String> {

  Optional<SharedDataObjectEntity> findByShareAndSharedAsSchemaLowerAndSharedAsNameLower(
      ShareEntity share, String sharedAsSchemaLower, String sharedAsNameLower);

  Optional<SharedDataObjectEntity>
      findByShareAndSharedAsSchemaLowerAndSharedAsNameLowerAndTypeAndStatus(
          ShareEntity share,
          String sharedAsSchemaLower,
          String sharedAsNameLower,
          AssetType type,
          SharedObjectStatus status);

  boolean existsByShareAndSharedAsSchemaLowerAndSharedAsNameLower(
      ShareEntity share, String sharedAsSchemaLower, String sharedAsNameLower);

  Optional<SharedDataObjectEntity> findByShareAndNameLower(ShareEntity share, String nameLower);

  boolean existsByShareAndNameLower(ShareEntity share, String nameLower);

  Page<SharedDataObjectEntity>
      findByShareAndTypeAndStatusOrderBySharedAsSchemaLowerAscSharedAsNameLowerAsc(
          ShareEntity share, AssetType type, SharedObjectStatus status, Pageable pageable);

  Page<SharedDataObjectEntity>
      findByShareAndSharedAsSchemaLowerAndTypeAndStatusOrderBySharedAsNameLowerAsc(
          ShareEntity share,
          String sharedAsSchemaLower,
          AssetType type,
          SharedObjectStatus status,
          Pageable pageable);

  List<SharedDataObjectEntity> findByShareOrderBySharedAsSchemaLowerAscSharedAsNameLowerAsc(
      ShareEntity share);

  boolean existsByShareAndSharedAsSchemaLowerAndStatus(
      ShareEntity share, String sharedAsSchemaLower, SharedObjectStatus status);

  /** A schema grant, whatever its status, since even a broken one still occupies its alias. */
  Optional<SharedDataObjectEntity> findByShareAndSharedAsSchemaLowerAndType(
      ShareEntity share, String sharedAsSchemaLower, AssetType type);

  Optional<SharedDataObjectEntity> findByShareAndSharedAsSchemaLowerAndTypeAndStatus(
      ShareEntity share, String sharedAsSchemaLower, AssetType type, SharedObjectStatus status);

  List<SharedDataObjectEntity> findByShareAndTypeAndStatusOrderBySharedAsSchemaLowerAsc(
      ShareEntity share, AssetType type, SharedObjectStatus status);

  /**
   * Unpaged counterparts of the listing queries, for the case where stored objects have to be merged
   * with a shared schema's contents before either can be paged.
   */
  List<SharedDataObjectEntity>
      findByShareAndTypeAndStatusOrderBySharedAsSchemaLowerAscSharedAsNameLowerAsc(
          ShareEntity share, AssetType type, SharedObjectStatus status);

  List<SharedDataObjectEntity> findByShareAndSharedAsSchemaLowerAndTypeAndStatusOrderBySharedAsNameLowerAsc(
      ShareEntity share, String sharedAsSchemaLower, AssetType type, SharedObjectStatus status);

  void deleteByShare(ShareEntity share);

  /**
   * Schema names are case-insensitive, so objects shared as {@code Sales.orders} and {@code
   * sales.items} are one schema. Grouping on the folded name collapses them and picks a single
   * spelling to serve.
   */
  @Query(
      value =
          "select min(o.sharedAsSchema) from SharedDataObjectEntity o where o.share = :share "
              + "and o.status = :status group by o.sharedAsSchemaLower "
              + "order by o.sharedAsSchemaLower asc",
      countQuery =
          "select count(distinct o.sharedAsSchemaLower) from SharedDataObjectEntity o "
              + "where o.share = :share and o.status = :status")
  Page<String> findSchemaNames(
      @Param("share") ShareEntity share,
      @Param("status") SharedObjectStatus status,
      Pageable pageable);
}
