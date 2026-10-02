package io.opensharing.share;

import io.opensharing.BaseEntity;
import io.opensharing.recipient.RecipientEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** One privilege a recipient holds on a share. */
@Entity
@Table(
    name = "os_share_permissions",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_share_permission",
            columnNames = {"share_id", "recipient_id", "privilege"}))
public class SharePermissionEntity extends BaseEntity {

  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "share_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private ShareEntity share;

  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "recipient_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private RecipientEntity recipient;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private SharePrivilege privilege;

  public ShareEntity getShare() {
    return share;
  }

  public void setShare(ShareEntity share) {
    this.share = share;
  }

  public RecipientEntity getRecipient() {
    return recipient;
  }

  public void setRecipient(RecipientEntity recipient) {
    this.recipient = recipient;
  }

  public SharePrivilege getPrivilege() {
    return privilege;
  }

  public void setPrivilege(SharePrivilege privilege) {
    this.privilege = privilege;
  }
}
