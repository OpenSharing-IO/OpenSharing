package io.opensharing.recipient;

import io.opensharing.http.AdminJson;
import java.time.Duration;
import java.time.Instant;

/**
 * @param tokenExpirationSeconds lifetime of the replacement token, defaulting to the configured TTL
 * @param existingTokenExpireInSeconds how long the token being replaced keeps working, defaulting to
 *     the configured rotation grace. Zero cuts it off immediately.
 */
@AdminJson
public record RotateTokenRequest(Long tokenExpirationSeconds, Long existingTokenExpireInSeconds) {

  static final RotateTokenRequest DEFAULTS = new RotateTokenRequest(null, null);

  Instant expiresAt() {
    return tokenExpirationSeconds == null
        ? null
        : Instant.now().plus(Duration.ofSeconds(tokenExpirationSeconds));
  }

  Duration grace() {
    return existingTokenExpireInSeconds == null
        ? null
        : Duration.ofSeconds(existingTokenExpireInSeconds);
  }
}
