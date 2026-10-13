package io.opensharing.share;

import io.opensharing.ObjectNames;
import io.opensharing.Transactions;
import io.opensharing.auth.Ownership;
import io.opensharing.auth.UserContext;
import io.opensharing.http.ApiException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Storage for shares. Names are stored lowercase and looked up case-insensitively. Each public
 * method runs in its own transaction and returns shares with their properties loaded, so callers
 * can read them after it ends.
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

  /**
   * Applies the non-null fields to {@code share}, which must be loaded in the caller's transaction,
   * such as by {@link #getOwnedOrThrow(EntityManager, String, UserContext)}.
   */
  void update(
      ShareEntity share, String displayName, String comment, Map<String, String> properties) {
    if (displayName != null) {
      share.setDisplayName(displayName);
    }
    if (comment != null) {
      share.setComment(comment);
    }
    if (properties != null) {
      share.setProperties(properties);
    }
  }

  /** Looks up a share by name in any case. */
  public Optional<ShareEntity> find(String name) {
    return tx.inTransaction(true, em -> find(em, name, LockModeType.NONE));
  }

  /** Like {@link #find}, but fails with not-found when the share does not exist. */
  public ShareEntity getOrThrow(String name) {
    return tx.inTransaction(true, em -> getOrThrow(em, name, LockModeType.NONE));
  }

  /** Like {@link #getOrThrow}, but also fails unless {@code user} owns the share. */
  public ShareEntity getOwnedOrThrow(String name, UserContext user) {
    return tx.inTransaction(true, em -> checkOwner(getOrThrow(em, name, LockModeType.NONE), user));
  }

  /** Every share, regardless of owner, ordered by name. */
  public List<ShareEntity> list() {
    return tx.inTransaction(
        true,
        em ->
            em.createQuery("select s from ShareEntity s order by s.name", ShareEntity.class)
                .getResultList());
  }

  /** Deletes a share and the objects in it. Only the owner may delete it. */
  public void delete(String name, UserContext user) {
    tx.inTransaction(
        false,
        em -> {
          ShareEntity share = getOwnedOrThrow(em, name, user);
          // DELETE FROM os_shared_data_objects WHERE share_id = ?
          em.createQuery("delete from SharedDataObjectEntity o where o.share.id = :shareId")
              .setParameter("shareId", share.getId())
              .executeUpdate();
          em.remove(share);
          return null;
        });
  }

  private static boolean exists(EntityManager em, String name) {
    return !em.createQuery("select s.id from ShareEntity s where s.name = :name", String.class)
        .setParameter("name", name)
        .setMaxResults(1)
        .getResultList()
        .isEmpty();
  }

  private static Optional<ShareEntity> find(EntityManager em, String name, LockModeType lock) {
    return em.createQuery("select s from ShareEntity s where s.name = :name", ShareEntity.class)
        .setParameter("name", ObjectNames.normalize(name))
        .setLockMode(lock)
        .getResultStream()
        .findFirst();
  }

  private static ShareEntity getOrThrow(EntityManager em, String name, LockModeType lock) {
    return find(em, name, lock)
        .orElseThrow(() -> ApiException.notFound("share '" + name + "' does not exist"));
  }

  /**
   * Like {@link #getOwnedOrThrow(String, UserContext)}, in the caller's transaction. Locks the
   * share's row, so changes to one share run one at a time and each sees what the one before it
   * wrote.
   */
  ShareEntity getOwnedOrThrow(EntityManager em, String name, UserContext user) {
    return checkOwner(getOrThrow(em, name, LockModeType.PESSIMISTIC_WRITE), user);
  }

  private static ShareEntity checkOwner(ShareEntity share, UserContext user) {
    Ownership.check(user, share.getOwnerId(), "share '" + share.getName() + "'");
    return share;
  }
}
