package io.opensharing.recipient;

import io.opensharing.auth.Secrets;
import io.opensharing.config.OpenSharingProperties;
import io.opensharing.http.ApiException;
import io.opensharing.principal.Caller;
import io.opensharing.protocol.ProfileFile;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, rotates, activates and authenticates recipient bearer tokens.
 *
 * <p>Issuing a token only creates an activation link. The bearer token itself is minted when the
 * recipient opens that link, is returned inside a profile file exactly once, and is stored only as a
 * SHA-256 hash.
 *
 * <p>A recipient gets its first token when it is created, and every later one by rotation, so there
 * is no way to accumulate credentials by accident. Rotation mints the replacement and lets the tokens
 * it supersedes keep working for a grace period, which is what gives the recipient time to install
 * the new profile file. Authentication accepts any token that is still usable, so both work during
 * that window.
 */
@Service
@Transactional
public class RecipientTokenService {

  private final RecipientTokenRepository tokens;
  private final OpenSharingProperties properties;

  public RecipientTokenService(RecipientTokenRepository tokens, OpenSharingProperties properties) {
    this.tokens = tokens;
    this.properties = properties;
  }

  /** An issued-but-not-yet-activated token and the one-time URL that reveals it. */
  public record IssuedToken(RecipientTokenEntity token, String activationUrl) {}

  /**
   * Replaces whatever the recipient is holding. Tokens it supersedes stay usable for {@code grace} so
   * the recipient can install the new profile file first; a zero grace revokes them immediately. A
   * superseded token that was never activated is always revoked at once, since nobody holds it.
   */
  public IssuedToken rotate(
      RecipientEntity recipient, Caller author, Instant expiresAt, Duration grace) {
    Instant now = Instant.now();
    Duration window = grace != null ? grace : properties.getRecipientTokens().getRotationGrace();
    boolean immediate = window == null || window.isNegative() || window.isZero();
    for (RecipientTokenEntity superseded : tokens.findByRecipientAndRevokedAtIsNull(recipient)) {
      superseded.setSupersededAt(now);
      if (immediate || !superseded.isActivated()) {
        revoke(superseded, now);
      } else {
        expireBy(superseded, now.plus(window));
      }
    }
    return issue(recipient, author, expiresAt);
  }

  IssuedToken issue(RecipientEntity recipient, Caller author, Instant expiresAt) {
    Instant now = Instant.now();
    Instant expiration = expiresAt != null ? expiresAt : defaultExpiration(now);
    if (expiration != null && !expiration.isAfter(now)) {
      throw ApiException.invalidParameter("the token expiration must be in the future");
    }

    String nonce = Secrets.newActivationNonce();
    RecipientTokenEntity token = new RecipientTokenEntity();
    token.setRecipient(recipient);
    token.setCreatedBy(author.principalId());
    token.setActivationNonceHash(Secrets.sha256(nonce));
    token.setActivationExpiresAt(now.plus(activationTtl()));
    token.setExpiresAt(expiration);
    tokens.save(token);

    return new IssuedToken(token, activationUrl(nonce));
  }

  /**
   * Mints the bearer token behind an activation link and returns the recipient's profile file. The
   * link is consumed in the process and cannot be opened again.
   */
  public ProfileFile activate(String nonce) {
    RecipientTokenEntity token =
        tokens
            .findByActivationNonceHash(Secrets.sha256(nonce))
            .orElseThrow(
                () -> ApiException.notFound("this activation link is invalid or already used"));
    if (!token.isActivatable(Instant.now())) {
      throw ApiException.notFound("this activation link is invalid or already used");
    }

    String bearerToken = Secrets.newToken();
    token.setTokenHash(Secrets.sha256(bearerToken));
    token.setActivationNonceHash(null);
    token.setActivated(true);
    tokens.save(token);

    return new ProfileFile(
        ProfileFile.CURRENT_VERSION,
        endpoint(),
        endpoint() + "/iceberg",
        bearerToken,
        token.getExpiresAt() == null ? null : token.getExpiresAt().toString());
  }

  @Transactional(readOnly = true)
  public Optional<RecipientTokenEntity> findUsableToken(String bearerToken) {
    return tokens
        .findByTokenHash(Secrets.sha256(bearerToken))
        .filter(token -> token.isUsable(Instant.now()));
  }

  @Transactional(readOnly = true)
  public List<RecipientTokenEntity> listTokens(RecipientEntity recipient) {
    return tokens.findByRecipientOrderByCreatedAtDesc(recipient);
  }

  private void revoke(RecipientTokenEntity token, Instant now) {
    token.setRevokedAt(now);
    token.setActivationNonceHash(null);
    tokens.save(token);
  }

  /** Brings a token's expiration forward, never pushing it out. */
  private void expireBy(RecipientTokenEntity token, Instant deadline) {
    if (token.getExpiresAt() == null || token.getExpiresAt().isAfter(deadline)) {
      token.setExpiresAt(deadline);
    }
    token.setActivationNonceHash(null);
    tokens.save(token);
  }

  private Instant defaultExpiration(Instant now) {
    Duration ttl = properties.getRecipientTokens().getDefaultTtl();
    return ttl == null ? null : now.plus(ttl);
  }

  private Duration activationTtl() {
    Duration ttl = properties.getActivation().getTtl();
    return ttl == null ? Duration.ofHours(72) : ttl;
  }

  private String activationUrl(String nonce) {
    return baseUrl() + properties.getActivation().getBasePath() + "/" + nonce;
  }

  private String endpoint() {
    return baseUrl() + properties.getProtocolPrefix();
  }

  private String baseUrl() {
    String base = properties.getActivation().getExternalBaseUrl();
    return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }
}
