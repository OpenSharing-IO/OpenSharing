package io.opensharing.recipient;

import io.opensharing.ObjectNames;
import io.opensharing.auth.UserContext;
import io.opensharing.http.ApiException;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storage for recipients and their tokens. Names are stored lowercase and looked up
 * case-insensitively. Each method runs in its own transaction, so returned entities are detached.
 */
@Service
@Transactional
public class RecipientStore {

  private final RecipientRepository recipients;
  private final RecipientTokenRepository tokens;

  public RecipientStore(RecipientRepository recipients, RecipientTokenRepository tokens) {
    this.recipients = recipients;
    this.tokens = tokens;
  }

  /**
   * Creates a recipient owned by {@code author} and its first token holding {@code
   * activationCode}. {@code name} must already be validated and lowercase. Fails with
   * already-exists when the name is taken.
   */
  public RecipientEntity create(
      UserContext author,
      String name,
      String comment,
      AuthenticationType authenticationType,
      String activationCode) {
    if (recipients.existsByName(name)) {
      throw ApiException.alreadyExists("recipient '" + name + "' already exists");
    }
    RecipientEntity recipient = new RecipientEntity();
    recipient.setName(name);
    recipient.setComment(comment);
    recipient.setOwnerId(author.userId());
    recipient.setAuthenticationType(authenticationType);
    recipient = recipients.save(recipient);
    RecipientTokenEntity token = new RecipientTokenEntity();
    token.setRecipient(recipient);
    token.setActivationCode(activationCode);
    tokens.save(token);
    return recipient;
  }

  /** Only non-null fields are applied. Only the owner may update the recipient. */
  public RecipientEntity update(UserContext user, String name, String comment) {
    RecipientEntity recipient = requireOwned(name, user);
    if (comment != null) {
      recipient.setComment(comment);
    }
    return recipients.save(recipient);
  }

  /** Looks up a recipient by name in any case. */
  @Transactional(readOnly = true)
  public Optional<RecipientEntity> find(String name) {
    return recipients.findByName(ObjectNames.normalize(name));
  }

  /** Like {@link #find}, but fails with not-found when the recipient does not exist. */
  @Transactional(readOnly = true)
  public RecipientEntity require(String name) {
    return find(name)
        .orElseThrow(() -> ApiException.notFound("recipient '" + name + "' does not exist"));
  }

  /** Like {@link #require}, but also fails unless {@code user} owns the recipient. */
  @Transactional(readOnly = true)
  public RecipientEntity requireOwned(String name, UserContext user) {
    RecipientEntity recipient = require(name);
    user.requireOwner(recipient.getOwnerId(), "recipient '" + recipient.getName() + "'");
    return recipient;
  }

  /** The newest token's activation code, or null when there is none. */
  @Transactional(readOnly = true)
  public String findActivationCode(RecipientEntity recipient) {
    return tokens
        .findFirstByRecipientOrderByCreatedAtDesc(recipient)
        .map(RecipientTokenEntity::getActivationCode)
        .orElse(null);
  }

  /** Lists all recipients ordered by name, regardless of owner. */
  @Transactional(readOnly = true)
  public Page<RecipientEntity> list(Pageable pageable) {
    return recipients.findAllByOrderByNameAsc(pageable);
  }

  /** Deletes a recipient and, through the cascade, its tokens. Only the owner may delete it. */
  public void delete(String name, UserContext user) {
    RecipientEntity recipient = requireOwned(name, user);
    recipients.delete(recipient);
  }
}
