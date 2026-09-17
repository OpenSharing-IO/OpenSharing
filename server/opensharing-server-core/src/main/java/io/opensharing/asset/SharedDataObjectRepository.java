package io.opensharing.asset;

import io.opensharing.share.ShareEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** JPA access to objects included in shares. */
public interface SharedDataObjectRepository
    extends JpaRepository<SharedDataObjectEntity, String> {

  boolean existsByShareAndName(ShareEntity share, String name);

  boolean existsByShareAndSharedAsSchemaAndSharedAsTable(
      ShareEntity share, String sharedAsSchema, String sharedAsTable);

  Optional<SharedDataObjectEntity> findByShareAndName(ShareEntity share, String name);

  Optional<SharedDataObjectEntity> findByShareAndSharedAsSchemaAndSharedAsTable(
      ShareEntity share, String sharedAsSchema, String sharedAsTable);
}
