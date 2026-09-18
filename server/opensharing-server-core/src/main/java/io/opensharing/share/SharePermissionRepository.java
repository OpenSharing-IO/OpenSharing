package io.opensharing.share;

import io.opensharing.recipient.RecipientEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** JPA access to share permissions. */
public interface SharePermissionRepository extends JpaRepository<SharePermissionEntity, String> {

  Optional<SharePermissionEntity> findByShareAndRecipientAndPrivilege(
      ShareEntity share, RecipientEntity recipient, SharePrivilege privilege);

  boolean existsByShareAndRecipientAndPrivilege(
      ShareEntity share, RecipientEntity recipient, SharePrivilege privilege);

  List<SharePermissionEntity> findByShareOrderByRecipient_NameAsc(ShareEntity share);
}
