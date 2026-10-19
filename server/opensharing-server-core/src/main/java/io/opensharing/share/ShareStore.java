package io.opensharing.share;

import io.opensharing.ObjectNames;
import io.opensharing.Transactions;
import io.opensharing.auth.UserContext;
import io.opensharing.http.ApiException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Storage for shares. Names are stored lowercase and looked up case-insensitively. Each method runs
 * in its own transaction, so returned entities are detached and must be fully loaded. Each loaded
 * share also runs {@code SELECT * FROM os_share_properties WHERE share_id = ?}.
 */
public class ShareStore {

  private final Transactions tx;

  public ShareStore(Transactions tx) {
    this.tx = tx;
  }

  /**
   * Creates a share owned by {@code author}. {@code name} must already be validated and lowercase.
   * Fails with already-exists when the name is taken.
   */
  public ShareEntity create(
      UserContext author,
      String name,
      String displayName,
      String comment,
      Map<String, String> properties) {
    return tx.inTransaction(
        false,
        em -> {
          if (exists(em, name)) {
            throw ApiException.alreadyExists("share '" + name + "' already exists");
          }
          ShareEntity share = new ShareEntity();
          share.setName(name);
          share.setDisplayName(displayName);
          share.setComment(comment);
          share.setProperties(properties);
          share.setOwnerId(author.userId());
          em.persist(share);
          return share;
        });
  }

  /** Only non-null fields are applied. Only the owner may update the share. */
  public ShareEntity update(
      UserContext user,
      String name,
      String displayName,
      String comment,
      Map<String, String> properties) {
    return tx.inTransaction(
        false,
        em -> {
          ShareEntity share = requireOwned(em, name, user);
          if (displayName != null) {
            share.setDisplayName(displayName);
          }
          if (comment != null) {
            share.setComment(comment);
          }
          if (properties != null) {
            share.setProperties(properties);
          }
          return share;
        });
  }

  /** Looks up a share by name in any case. */
  public Optional<ShareEntity> find(String name) {
    return tx.inTransaction(true, em -> find(em, name, LockModeType.NONE));
  }

  /** Like {@link #find}, but fails with not-found when the share does not exist. */
  public ShareEntity require(String name) {
    return tx.inTransaction(true, em -> require(em, name, LockModeType.NONE));
  }

  /** Like {@link #require}, but also fails unless {@code user} owns the share. */
  public ShareEntity requireOwned(String name, UserContext user) {
    return tx.inTransaction(true, em -> requireOwner(require(em, name, LockModeType.NONE), user));
  }

  /** {@code SELECT * FROM os_shares ORDER BY name ASC}: every share, regardless of owner. */
  public List<ShareEntity> list() {
    return tx.inTransaction(
        true,
        em ->
            em.createQuery("select s from ShareEntity s order by s.name", ShareEntity.class)
                .getResultList());
  }

  /** Deletes a share. Only the owner may delete it. */
  public void delete(String name, UserContext user) {
    tx.inTransaction(
        false,
        em -> {
          em.remove(requireOwned(em, name, user));
          return null;
        });
  }

  /** {@code SELECT id FROM os_shares WHERE name = ? LIMIT 1} */
  private static boolean exists(EntityManager em, String name) {
    return !em.createQuery("select s.id from ShareEntity s where s.name = :name", String.class)
        .setParameter("name", name)
        .setMaxResults(1)
        .getResultList()
        .isEmpty();
  }

  /** {@code SELECT * FROM os_shares WHERE name = ?}, plus {@code FOR UPDATE} under a lock. */
  private static Optional<ShareEntity> find(EntityManager em, String name, LockModeType lock) {
    return em.createQuery("select s from ShareEntity s where s.name = :name", ShareEntity.class)
        .setParameter("name", ObjectNames.normalize(name))
        .setLockMode(lock)
        .getResultStream()
        .findFirst();
  }

  private static ShareEntity require(EntityManager em, String name, LockModeType lock) {
    return find(em, name, lock)
        .orElseThrow(() -> ApiException.notFound("share '" + name + "' does not exist"));
  }

  /**
   * Locks the row of a share the caller owns, so changes to one share run one at a time and each
   * sees what the one before it wrote.
   */
  private static ShareEntity requireOwned(EntityManager em, String name, UserContext user) {
    return requireOwner(require(em, name, LockModeType.PESSIMISTIC_WRITE), user);
  }

  private static ShareEntity requireOwner(ShareEntity share, UserContext user) {
    user.requireOwner(share.getOwnerId(), "share '" + share.getName() + "'");
    return share;
  }
}
