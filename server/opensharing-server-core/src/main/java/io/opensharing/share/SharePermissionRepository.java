package io.opensharing.share;

import io.opensharing.recipient.RecipientEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** JPA access to share permissions. */
public interface SharePermissionRepository extends JpaRepository<SharePermissionEntity, String> {

  Optional<SharePermissionEntity> findByShareAndRecipientAndPrivilege(
      ShareEntity share, RecipientEntity recipient, SharePrivilege privilege);

  boolean existsByShareAndRecipientAndPrivilege(
      ShareEntity share, RecipientEntity recipient, SharePrivilege privilege);

  List<SharePermissionEntity> findByShareOrderByRecipient_NameAsc(ShareEntity share);

  @Query(
      value =
          "select p.share from SharePermissionEntity p "
              + "where p.recipient = :recipient and p.privilege = :privilege "
              + "order by p.share.name asc",
      countQuery =
          "select count(p) from SharePermissionEntity p "
              + "where p.recipient = :recipient and p.privilege = :privilege")
  Page<ShareEntity> findSharesForRecipient(
      @Param("recipient") RecipientEntity recipient,
      @Param("privilege") SharePrivilege privilege,
      Pageable pageable);
}
