package io.opensharing.recipient;

import io.opensharing.ObjectNames;
import io.opensharing.auth.UserContext;
import io.opensharing.http.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Storage for recipients. Names are stored lowercase and looked up case-insensitively. */
@Service
@Transactional
public class RecipientStore {

  private final RecipientRepository recipients;
  private final RecipientTokenRepository tokens;

  public RecipientStore(RecipientRepository recipients, RecipientTokenRepository tokens) {
    this.recipients = recipients;
    this.tokens = tokens;
  }

  public RecipientEntity create(
      UserContext author,
      String name,
      String comment,
      AuthenticationType authenticationType,
      String activationCode,
      Instant expiresAt) {
    if (recipients.existsByName(name)) {
      throw ApiException.alreadyExists("recipient '" + name + "' already exists");
    }
    RecipientEntity recipient = new RecipientEntity();
    recipient.setName(name);
    recipient.setComment(comment);
    recipient.setOwnerId(author.id());
    recipient.setAuthenticationType(authenticationType);
    recipient = recipients.save(recipient);
    RecipientTokenEntity token = new RecipientTokenEntity();
    token.setRecipient(recipient);
    token.setActivationCode(activationCode);
    token.setExpiresAt(expiresAt);
    tokens.save(token);
    return recipient;
  }

  public RecipientEntity update(UserContext user, String name, String comment) {
    RecipientEntity recipient = requireOwned(name, user);
    if (comment != null) {
      recipient.setComment(comment);
    }
    return recipients.save(recipient);
  }

  @Transactional(readOnly = true)
  public Optional<RecipientEntity> find(String name) {
    return recipients.findByName(ObjectNames.normalize(name));
  }

  @Transactional(readOnly = true)
  public RecipientEntity require(String name) {
    return find(name)
        .orElseThrow(() -> ApiException.notFound("recipient '" + name + "' does not exist"));
  }

  @Transactional(readOnly = true)
  public RecipientEntity requireById(String id) {
    return recipients
        .findById(id)
        .orElseThrow(() -> ApiException.unauthenticated("recipient no longer exists"));
  }

  @Transactional(readOnly = true)
  public RecipientEntity requireOwned(String name, UserContext user) {
    RecipientEntity recipient = require(name);
    user.requireOwner(recipient.getOwnerId(), "recipient '" + recipient.getName() + "'");
    return recipient;
  }

  @Transactional(readOnly = true)
  public String findActivationCode(RecipientEntity recipient) {
    return tokens
        .findFirstByRecipientOrderByCreatedAtDesc(recipient)
        .map(RecipientTokenEntity::getActivationCode)
        .orElse(null);
  }

  @Transactional(readOnly = true)
  public Page<RecipientEntity> list(Pageable pageable) {
    return recipients.findAllByOrderByNameAsc(pageable);
  }

  public void delete(String name, UserContext user) {
    RecipientEntity recipient = requireOwned(name, user);
    recipients.delete(recipient);
  }

  /** Persists a bearer hash and consumes a valid one-time activation code. The row is locked so a concurrent redeem of the same code waits, then 404s. */
  public RecipientTokenEntity activate(String activationCode, String tokenHash, Instant now) {
    RecipientTokenEntity token =
        tokens
            .findByActivationCode(activationCode)
            .orElseThrow(() -> ApiException.notFound("activation code does not exist"));
    if (token.isActivated()
        || (token.getExpiresAt() != null && !token.getExpiresAt().isAfter(now))) {
      throw ApiException.notFound("activation code does not exist");
    }
    token.setTokenHash(tokenHash);
    token.setActivationCode(null);
    token.setActivated(true);
    return tokens.save(token);
  }

  @Transactional(readOnly = true)
  public Optional<RecipientTokenEntity> findUsableToken(String tokenHash, Instant now) {
    return tokens
        .findByTokenHash(tokenHash)
        .filter(
            token ->
                token.isActivated()
                    && (token.getExpiresAt() == null || token.getExpiresAt().isAfter(now)));
  }

  /**
   * Supersedes live credentials and persists a pending replacement. An unactivated credential or a
   * zero grace window expires immediately because no recipient should keep using it.
   */
  public RecipientTokenEntity rotate(
      RecipientEntity recipient,
      String activationCode,
      Instant expiresAt,
      Instant now,
      Duration grace) {
    for (RecipientTokenEntity current : tokens.findByRecipient(recipient)) {
      if (current.getExpiresAt() != null && !current.getExpiresAt().isAfter(now)) {
        continue;
      }
      current.setSupersededAt(now);
      current.setActivationCode(null);
      if (!current.isActivated() || grace.isZero() || grace.isNegative()) {
        current.setExpiresAt(now);
      } else {
        Instant deadline = now.plus(grace);
        if (current.getExpiresAt() == null || current.getExpiresAt().isAfter(deadline)) {
          current.setExpiresAt(deadline);
        }
      }
      tokens.save(current);
    }
    RecipientTokenEntity replacement = new RecipientTokenEntity();
    replacement.setRecipient(recipient);
    replacement.setActivationCode(activationCode);
    replacement.setExpiresAt(expiresAt);
    return tokens.save(replacement);
  }
}
