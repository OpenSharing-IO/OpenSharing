package io.opensharing.recipient;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** JPA access to recipient tokens. */
public interface RecipientTokenRepository extends JpaRepository<RecipientTokenEntity, String> {

  /**
   * {@code SELECT * FROM os_recipient_tokens WHERE recipient_id = ? ORDER BY created_at DESC}
   * {@code LIMIT 1}
   */
  Optional<RecipientTokenEntity> findFirstByRecipientOrderByCreatedAtDesc(
      RecipientEntity recipient);
}
