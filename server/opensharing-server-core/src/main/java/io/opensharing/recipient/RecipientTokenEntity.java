package io.opensharing.recipient;

import io.opensharing.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** A token issued to a recipient. {@code activationCode} is stored plaintext until redeemed. */
@Entity
@Table(
    name = "os_recipient_tokens",
    uniqueConstraints =
        @UniqueConstraint(name = "uk_recipient_tokens_activation_code", columnNames = "activation_code"))
public class RecipientTokenEntity extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "recipient_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private RecipientEntity recipient;

  @Column(name = "activation_code", unique = true, length = 36)
  private String activationCode;

  public RecipientEntity getRecipient() {
    return recipient;
  }

  public void setRecipient(RecipientEntity recipient) {
    this.recipient = recipient;
  }

  public String getActivationCode() {
    return activationCode;
  }

  public void setActivationCode(String activationCode) {
    this.activationCode = activationCode;
  }
}
