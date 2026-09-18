package io.opensharing.recipient;

import io.opensharing.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * A recipient credential. Its activation code is plaintext until redeemed; only the bearer hash is
 * persisted.
 */
@Entity
@Table(
    name = "os_recipient_tokens",
    uniqueConstraints = {
      @UniqueConstraint(name = "uk_recipient_tokens_activation_code", columnNames = "activation_code"),
      @UniqueConstraint(name = "uk_recipient_tokens_token_hash", columnNames = "token_hash")
    })
public class RecipientTokenEntity extends BaseEntity {

  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "recipient_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private RecipientEntity recipient;

  @Column(name = "activation_code", unique = true, length = 36)
  private String activationCode;

  @Column(name = "token_hash", unique = true, length = 64)
  private String tokenHash;

  @Column(nullable = false)
  private boolean activated;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "superseded_at")
  private Instant supersededAt;

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

  public String getTokenHash() {
    return tokenHash;
  }

  public void setTokenHash(String tokenHash) {
    this.tokenHash = tokenHash;
  }

  public boolean isActivated() {
    return activated;
  }

  public void setActivated(boolean activated) {
    this.activated = activated;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  public Instant getSupersededAt() {
    return supersededAt;
  }

  public void setSupersededAt(Instant supersededAt) {
    this.supersededAt = supersededAt;
  }
}
