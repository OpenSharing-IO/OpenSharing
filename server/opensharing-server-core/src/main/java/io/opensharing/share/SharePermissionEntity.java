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

/**
 * One privilege a recipient holds on a share. {@code granted_at} is {@link BaseEntity#getCreatedAt()}
 * — the row exists because the grant was made.
 */
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
  private ShareEntity share;

  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "recipient_id", nullable = false)
  private RecipientEntity recipient;

  @Enumerated(EnumType.STRING)
  @Column(name = "privilege", nullable = false, length = 32)
  private SharePrivilege privilege;

  /** A catalog's own identity for whoever granted this privilege — see {@code ShareEntity}. */
  @Column(name = "granted_by", nullable = false, length = 255)
  private String grantedBy;

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

  public String getGrantedBy() {
    return grantedBy;
  }

  public void setGrantedBy(String grantedBy) {
    this.grantedBy = grantedBy;
  }
}
