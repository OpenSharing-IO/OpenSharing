package io.opensharing.recipient;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/** JPA access to recipient tokens. */
public interface RecipientTokenRepository extends JpaRepository<RecipientTokenEntity, String> {

  Optional<RecipientTokenEntity> findFirstByRecipientOrderByCreatedAtDesc(RecipientEntity recipient);

  Optional<RecipientTokenEntity> findByTokenHash(String tokenHash);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<RecipientTokenEntity> findByActivationCode(String activationCode);

  List<RecipientTokenEntity> findByRecipient(RecipientEntity recipient);
}
