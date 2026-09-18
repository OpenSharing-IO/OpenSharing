package io.opensharing.share;

import io.opensharing.BaseEntity;
import io.opensharing.recipient.RecipientEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** A recipient that is allowed to access a share. */
@Entity
@Table(
    name = "os_share_recipients",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_share_recipients",
            columnNames = {"share_id", "recipient_id"}))
public class ShareRecipientEntity extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "share_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private ShareEntity share;

  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "recipient_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private RecipientEntity recipient;

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
}
