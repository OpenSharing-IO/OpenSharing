package io.opensharing.share;

import io.opensharing.Transactions;
import io.opensharing.http.ApiException;
import io.opensharing.recipient.RecipientEntity;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;

/**
 * Storage for privileges recipients hold on shares. Callers check share ownership before granting
 * or revoking. Each method runs in its own transaction; each loaded permission also loads its
 * share and recipient.
 */
public class SharePermissionStore {

  private final Transactions tx;

  public SharePermissionStore(Transactions tx) {
    this.tx = tx;
  }

  /** Granting a privilege the recipient already holds leaves the original row untouched. */
  public void grant(ShareEntity share, RecipientEntity recipient, SharePrivilege privilege) {
    tx.inTransaction(
        false,
        em -> {
          if (find(em, share, recipient, privilege).isEmpty()) {
            SharePermissionEntity permission = new SharePermissionEntity();
            permission.setShare(em.getReference(ShareEntity.class, share.getId()));
            permission.setRecipient(em.getReference(RecipientEntity.class, recipient.getId()));
            permission.setPrivilege(privilege);
            em.persist(permission);
          }
          return null;
        });
  }

  /** Revokes a privilege. Fails with not-found when the recipient does not hold it. */
  public void revoke(ShareEntity share, RecipientEntity recipient, SharePrivilege privilege) {
    tx.inTransaction(
        false,
        em -> {
          SharePermissionEntity permission =
              find(em, share, recipient, privilege)
                  .orElseThrow(
                      () ->
                          ApiException.notFound(
                              "recipient '"
                                  + recipient.getName()
                                  + "' does not hold "
                                  + privilege
                                  + " on share '"
                                  + share.getName()
                                  + "'"));
          em.remove(permission);
          return null;
        });
  }

  /**
   * {@code SELECT p.* FROM os_share_permissions p JOIN os_recipients r ON r.id = p.recipient_id
   * WHERE p.share_id = ? ORDER BY r.name, p.privilege LIMIT ? OFFSET ?}. A recipient holds each
   * privilege at most once, so the order is total.
   */
  public List<SharePermissionEntity> list(ShareEntity share, int offset, int limit) {
    return tx.inTransaction(
        true,
        em ->
            em.createQuery(
                    "select p from SharePermissionEntity p join fetch p.share"
                        + " join fetch p.recipient r where p.share.id = :shareId"
                        + " order by r.name, p.privilege",
                    SharePermissionEntity.class)
                .setParameter("shareId", share.getId())
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList());
  }

  /**
   * {@code SELECT * FROM os_share_permissions WHERE share_id = ? AND recipient_id = ? AND
   * privilege = ?}
   */
  private static Optional<SharePermissionEntity> find(
      EntityManager em, ShareEntity share, RecipientEntity recipient, SharePrivilege privilege) {
    return em.createQuery(
            "select p from SharePermissionEntity p where p.share.id = :shareId"
                + " and p.recipient.id = :recipientId and p.privilege = :privilege",
            SharePermissionEntity.class)
        .setParameter("shareId", share.getId())
        .setParameter("recipientId", recipient.getId())
        .setParameter("privilege", privilege)
        .getResultStream()
        .findFirst();
  }
}
