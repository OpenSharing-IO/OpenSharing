package io.opensharing.recipient;

import io.opensharing.ObjectNames;
import io.opensharing.Transactions;
import io.opensharing.auth.UserContext;
import io.opensharing.http.ApiException;
import jakarta.persistence.EntityManager;
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
   * activationCode}. {@code name} must already be validated and lowercase. Fails with
   * already-exists when the name is taken.
   */
  public RecipientEntity create(
      UserContext author,
      String name,
      String comment,
      AuthenticationType authenticationType,
      String activationCode) {
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
}
