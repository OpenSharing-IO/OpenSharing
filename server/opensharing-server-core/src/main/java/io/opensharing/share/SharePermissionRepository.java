package io.opensharing.share;

import io.opensharing.recipient.RecipientEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SharePermissionRepository extends JpaRepository<SharePermissionEntity, String> {

  Optional<SharePermissionEntity> findByShareAndRecipientAndPrivilege(
      ShareEntity share, RecipientEntity recipient, SharePrivilege privilege);

  boolean existsByShareAndRecipientAndPrivilege(
      ShareEntity share, RecipientEntity recipient, SharePrivilege privilege);

  List<SharePermissionEntity> findByShareOrderByRecipientNameLowerAsc(ShareEntity share);

  List<SharePermissionEntity> findByRecipientOrderByShareNameLowerAsc(RecipientEntity recipient);

  void deleteByShare(ShareEntity share);

  void deleteByRecipient(RecipientEntity recipient);

  @Query(
      value =
          "select distinct p.share from SharePermissionEntity p where p.recipient = :recipient "
              + "order by p.share.nameLower asc",
      countQuery =
          "select count(distinct p.share) from SharePermissionEntity p "
              + "where p.recipient = :recipient")
  Page<ShareEntity> findSharesForRecipient(
      @Param("recipient") RecipientEntity recipient, Pageable pageable);
}
