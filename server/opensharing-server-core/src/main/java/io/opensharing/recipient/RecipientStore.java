package io.opensharing.recipient;

import io.opensharing.ObjectNames;
import io.opensharing.Transactions;
import io.opensharing.auth.UserContext;
import io.opensharing.http.ApiException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
   * activationCode}, expiring at {@code expiresAt} (null for never). {@code name} must already be
   * validated and lowercase. Fails with already-exists when the name is taken.
   */
  public RecipientEntity create(
      UserContext author,
      String name,
      String comment,
      AuthenticationType authenticationType,
      String activationCode,
      Instant expiresAt) {
    return tx.inTransaction(
        false,
        em -> {
          if (find(em, name).isPresent()) {
            throw ApiException.alreadyExists("recipient '" + name + "' already exists");
          }
          RecipientEntity recipient = new RecipientEntity();
          recipient.setName(name);
          recipient.setComment(comment);
          recipient.setOwnerId(author.userId());
          recipient.setAuthenticationType(authenticationType);
          em.persist(recipient);
          RecipientTokenEntity token = new RecipientTokenEntity();
          token.setRecipient(recipient);
          token.setActivationCode(activationCode);
          token.setExpiresAt(expiresAt);
          em.persist(token);
          return recipient;
        });
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

  /** The newest token's activation code, or null once it has been redeemed. */
  public String findActivationCode(RecipientEntity recipient) {
    return tx.inTransaction(
        true,
        em -> {
          List<String> codes =
              em.createQuery(
                      "select t.activationCode from RecipientTokenEntity t"
                          + " where t.recipient.id = :recipientId order by t.createdAt desc",
                      String.class)
                  .setParameter("recipientId", recipient.getId())
                  .setMaxResults(1)
                  .getResultList();
          return codes.isEmpty() ? null : codes.get(0);
        });
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

  /**
   * Persists a bearer hash and consumes a valid one-time activation code. The row is locked so a
   * concurrent redeem of the same code waits, then fails with not-found.
   */
  public RecipientTokenEntity activate(String activationCode, String tokenHash, Instant now) {
    return tx.inTransaction(
        false,
        em -> {
          // SELECT * FROM os_recipient_tokens WHERE activation_code = ? FOR UPDATE
          RecipientTokenEntity token =
              em.createQuery(
                      "select t from RecipientTokenEntity t where t.activationCode = :code",
                      RecipientTokenEntity.class)
                  .setParameter("code", activationCode)
                  .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                  .getResultStream()
                  .findFirst()
                  .orElseThrow(() -> ApiException.notFound("activation code does not exist"));
          if (token.isActivated()
              || (token.getExpiresAt() != null && !token.getExpiresAt().isAfter(now))) {
            throw ApiException.notFound("activation code does not exist");
          }
          token.setTokenHash(tokenHash);
          token.setActivationCode(null);
          token.setActivated(true);
          return token;
        });
  }

  /**
   * Supersedes live credentials of the recipient and persists a pending replacement. An
   * unactivated credential or a zero grace window expires immediately because no recipient should
   * keep using it. Only the owner may rotate.
   */
  public RecipientTokenEntity rotate(
      UserContext user,
      String name,
      String activationCode,
      Instant expiresAt,
      Instant now,
      Duration grace) {
    return tx.inTransaction(
        false,
        em -> {
          RecipientEntity recipient = requireOwned(em, name, user);
          // SELECT * FROM os_recipient_tokens WHERE recipient_id = ?
          List<RecipientTokenEntity> current =
              em.createQuery(
                      "select t from RecipientTokenEntity t where t.recipient.id = :recipientId",
                      RecipientTokenEntity.class)
                  .setParameter("recipientId", recipient.getId())
                  .getResultList();
          for (RecipientTokenEntity token : current) {
            if (token.getExpiresAt() != null && !token.getExpiresAt().isAfter(now)) {
              continue;
            }
            token.setSupersededAt(now);
            token.setActivationCode(null);
            if (!token.isActivated() || grace.isZero() || grace.isNegative()) {
              token.setExpiresAt(now);
            } else {
              Instant deadline = now.plus(grace);
              if (token.getExpiresAt() == null || token.getExpiresAt().isAfter(deadline)) {
                token.setExpiresAt(deadline);
              }
            }
          }
          RecipientTokenEntity replacement = new RecipientTokenEntity();
          replacement.setRecipient(recipient);
          replacement.setActivationCode(activationCode);
          replacement.setExpiresAt(expiresAt);
          em.persist(replacement);
          return replacement;
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
}
