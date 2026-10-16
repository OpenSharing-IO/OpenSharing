package io.opensharing.recipient;

/**
 * POST body to rotate a recipient's token.
 *
 * @param tokenExpirationDays replacement token lifetime in days (required)
 * @param existingTokenExpireInSeconds old-token grace window; defaults to 0 (revoke immediately)
 */
public record RotateTokenRequest(Long tokenExpirationDays, Long existingTokenExpireInSeconds) {}
