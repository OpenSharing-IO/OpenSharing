package io.opensharing.recipient;

/**
 * Optional POST body to rotate a recipient's token. An omitted body uses the defaults.
 *
 * @param tokenExpirationDays replacement token lifetime; defaults to configured token TTL
 * @param existingTokenExpireInSeconds old-token grace window; defaults to 0 (revoke immediately)
 */
public record RotateTokenRequest(Long tokenExpirationDays, Long existingTokenExpireInSeconds) {

  static final RotateTokenRequest DEFAULTS = new RotateTokenRequest(null, null);
}
