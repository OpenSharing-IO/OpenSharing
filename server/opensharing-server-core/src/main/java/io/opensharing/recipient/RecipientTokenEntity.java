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

/**
 * A bearer credential issued to a recipient. The secret does not exist until the recipient opens the
 * one-time activation URL, and only its SHA-256 hash is ever persisted, so the server never holds a
 * replayable secret at rest.
 *
 * <p>A rotated token is stamped with {@code superseded_at} and keeps working until it expires, which
 * is the window the recipient has to install its replacement.
 */
@Entity
@Table(
    name = "os_recipient_tokens",
    uniqueConstraints = {
      @UniqueConstraint(name = "uk_recipient_tokens_hash", columnNames = "token_hash"),
      @UniqueConstraint(
          name = "uk_recipient_tokens_activation",
          columnNames = "activation_nonce_hash")
    })
public class RecipientTokenEntity extends BaseEntity {

  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "recipient_id", nullable = false)
  private RecipientEntity recipient;

  /** Set when the token is minted at activation time. */
  @Column(name = "token_hash", length = 64)
  private String tokenHash;

  @Column(name = "activation_nonce_hash", length = 64)
  private String activationNonceHash;

  @Column(name = "activation_expires_at")
  private Instant activationExpiresAt;

  @Column(name = "activated", nullable = false)
  private boolean activated;

  /** Null means the token never expires. */
  @Column(name = "expires_at")
  private Instant expiresAt;

  /** When a rotation replaced this token. It stays usable until it expires. */
  @Column(name = "superseded_at")
  private Instant supersededAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  /** A catalog's own identity for whoever issued this token — see {@code ShareEntity}. */
  @Column(name = "created_by", nullable = false, length = 255)
  private String createdBy;

  public boolean isUsable(Instant now) {
    return activated && revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
  }

  public boolean isActivatable(Instant now) {
    return !activated
        && revokedAt == null
        && activationNonceHash != null
        && (activationExpiresAt == null || activationExpiresAt.isAfter(now))
        && (expiresAt == null || expiresAt.isAfter(now));
  }

  public RecipientEntity getRecipient() {
    return recipient;
  }

  public void setRecipient(RecipientEntity recipient) {
    this.recipient = recipient;
  }

  public void setTokenHash(String tokenHash) {
    this.tokenHash = tokenHash;
  }

  public void setActivationNonceHash(String activationNonceHash) {
    this.activationNonceHash = activationNonceHash;
  }

  public Instant getActivationExpiresAt() {
    return activationExpiresAt;
  }

  public void setActivationExpiresAt(Instant activationExpiresAt) {
    this.activationExpiresAt = activationExpiresAt;
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

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public void setRevokedAt(Instant revokedAt) {
    this.revokedAt = revokedAt;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(String createdBy) {
    this.createdBy = createdBy;
  }
}
