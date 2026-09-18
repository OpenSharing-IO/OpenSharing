package io.opensharing.share;

import io.opensharing.recipient.RecipientEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** JPA access to share-recipient grants. */
public interface ShareRecipientRepository extends JpaRepository<ShareRecipientEntity, String> {

  boolean existsByShareAndRecipient(ShareEntity share, RecipientEntity recipient);

  Optional<ShareRecipientEntity> findByShareAndRecipient(ShareEntity share, RecipientEntity recipient);

  List<ShareRecipientEntity> findByShareOrderByRecipient_NameAsc(ShareEntity share);
}
