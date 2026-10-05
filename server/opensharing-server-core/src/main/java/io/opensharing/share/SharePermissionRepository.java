package io.opensharing.share;

import io.opensharing.recipient.RecipientEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * JPA access to share permissions. Each loaded permission also loads its share and recipient
 * eagerly.
 */
public interface SharePermissionRepository extends JpaRepository<SharePermissionEntity, String> {

  /**
   * {@code SELECT * FROM os_share_permissions WHERE share_id = ? AND recipient_id = ? AND
   * privilege = ?}
   */
  Optional<SharePermissionEntity> findByShareAndRecipientAndPrivilege(
      ShareEntity share, RecipientEntity recipient, SharePrivilege privilege);

  /**
   * {@code SELECT 1 FROM os_share_permissions WHERE share_id = ? AND recipient_id = ? AND
   * privilege = ? LIMIT 1}
   */
  boolean existsByShareAndRecipientAndPrivilege(
      ShareEntity share, RecipientEntity recipient, SharePrivilege privilege);

  /**
   * {@code SELECT p.* FROM os_share_permissions p JOIN os_recipients r ON r.id = p.recipient_id
   * WHERE p.share_id = ? ORDER BY r.name}
   */
  List<SharePermissionEntity> findByShareOrderByRecipient_NameAsc(ShareEntity share);
}
