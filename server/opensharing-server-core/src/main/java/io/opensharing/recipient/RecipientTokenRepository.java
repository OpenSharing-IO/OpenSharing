package io.opensharing.recipient;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecipientTokenRepository extends JpaRepository<RecipientTokenEntity, String> {

  Optional<RecipientTokenEntity> findByTokenHash(String tokenHash);

  Optional<RecipientTokenEntity> findByActivationNonceHash(String activationNonceHash);

  List<RecipientTokenEntity> findByRecipientOrderByCreatedAtDesc(RecipientEntity recipient);

  List<RecipientTokenEntity> findByRecipientAndRevokedAtIsNull(RecipientEntity recipient);

  void deleteByRecipient(RecipientEntity recipient);
}
