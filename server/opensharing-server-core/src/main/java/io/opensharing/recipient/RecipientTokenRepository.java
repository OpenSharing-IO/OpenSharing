package io.opensharing.recipient;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** JPA access to recipient tokens. */
public interface RecipientTokenRepository extends JpaRepository<RecipientTokenEntity, String> {

  Optional<RecipientTokenEntity> findFirstByRecipientOrderByCreatedAtDesc(RecipientEntity recipient);
}
