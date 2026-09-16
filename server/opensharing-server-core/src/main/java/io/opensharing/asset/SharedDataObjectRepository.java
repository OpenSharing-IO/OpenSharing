package io.opensharing.asset;

import io.opensharing.share.ShareEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** JPA access to objects included in shares. */
public interface SharedDataObjectRepository
    extends JpaRepository<SharedDataObjectEntity, String> {

  boolean existsByShareAndNameLower(ShareEntity share, String nameLower);

  boolean existsByShareAndSharedAsLower(ShareEntity share, String sharedAsLower);

  Optional<SharedDataObjectEntity> findByShareAndNameLower(
      ShareEntity share, String nameLower);

  Optional<SharedDataObjectEntity> findByShareAndSharedAsLower(
      ShareEntity share, String sharedAsLower);
}
