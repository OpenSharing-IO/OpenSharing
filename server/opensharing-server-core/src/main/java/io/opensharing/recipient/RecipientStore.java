package io.opensharing.recipient;

import io.opensharing.ObjectNames;
import io.opensharing.Transactions;
import io.opensharing.auth.UserContext;
import io.opensharing.http.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storage for recipients and their tokens. Names are stored lowercase and looked up
 * case-insensitively. Each method runs in its own transaction, so returned entities are detached.
 */
public class RecipientStore {

  private final Transactions tx;

  public RecipientStore(Transactions tx) {
    this.tx = tx;
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
      String activationCode,
      Instant expiresAt) {
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
    token.setExpiresAt(expiresAt);
    tokens.save(token);
    return recipient;
  }

  /** Only non-null fields are applied. Only the owner may update the recipient. */
  public RecipientEntity update(UserContext user, String name, String comment) {
    return tx.inTransaction(
        false,
        em -> {
          RecipientEntity recipient = requireOwned(em, name, user);
          if (comment != null) {
            recipient.setComment(comment);
          }
          return recipient;
        });
  }

  /** Looks up a recipient by name in any case. Fails with not-found when it does not exist. */
  public RecipientEntity require(String name) {
    return tx.inTransaction(true, em -> require(em, name));
  }

  /** The newest token's activation code, or null when there is none. */
  public String findActivationCode(RecipientEntity recipient) {
    return tx.inTransaction(
        true,
        em ->
            em.createQuery(
                    "select t.activationCode from RecipientTokenEntity t"
                        + " where t.recipient.id = :recipientId order by t.createdAt desc",
                    String.class)
                .setParameter("recipientId", recipient.getId())
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .orElse(null));
  }

  /** {@code SELECT * FROM os_recipients ORDER BY name ASC}: every recipient, of any owner. */
  public List<RecipientEntity> list() {
    return tx.inTransaction(
        true,
        em ->
            em.createQuery(
                    "select r from RecipientEntity r order by r.name", RecipientEntity.class)
                .getResultList());
  }

  /** Deletes a recipient, its tokens and its permissions. Only the owner may delete it. */
  public void delete(String name, UserContext user) {
    tx.inTransaction(
        false,
        em -> {
          RecipientEntity recipient = requireOwned(em, name, user);
          // DELETE FROM os_recipient_tokens WHERE recipient_id = ?
          em.createQuery("delete from RecipientTokenEntity t where t.recipient.id = :recipientId")
              .setParameter("recipientId", recipient.getId())
              .executeUpdate();
          // DELETE FROM os_share_permissions WHERE recipient_id = ?
          em.createQuery(
                  "delete from SharePermissionEntity p where p.recipient.id = :recipientId")
              .setParameter("recipientId", recipient.getId())
              .executeUpdate();
          em.remove(recipient);
          return null;
        });
  }

  /** {@code SELECT * FROM os_recipients WHERE name = ?} */
  private static Optional<RecipientEntity> find(EntityManager em, String name) {
    return em.createQuery(
            "select r from RecipientEntity r where r.name = :name", RecipientEntity.class)
        .setParameter("name", ObjectNames.normalize(name))
        .getResultStream()
        .findFirst();
  }

  private static RecipientEntity require(EntityManager em, String name) {
    return find(em, name)
        .orElseThrow(() -> ApiException.notFound("recipient '" + name + "' does not exist"));
  }

  private static RecipientEntity requireOwned(EntityManager em, String name, UserContext user) {
    RecipientEntity recipient = require(em, name);
    user.requireOwner(recipient.getOwnerId(), "recipient '" + recipient.getName() + "'");
    return recipient;
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
