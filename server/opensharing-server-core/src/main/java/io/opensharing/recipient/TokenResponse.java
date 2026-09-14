package io.opensharing.recipient;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.opensharing.http.AdminJson;
import java.time.Instant;

/** A token's lifecycle, never its secret. */
@AdminJson
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TokenResponse(
    String tokenId,
    String recipientId,
    boolean activated,
    Instant activationExpiresAt,
    Instant createdAt,
    String createdBy,
    Instant expiresAt,
    Instant supersededAt,
    Instant revokedAt) {

  public static TokenResponse from(RecipientTokenEntity token) {
    return new TokenResponse(
        token.getId(),
        token.getRecipient().getId(),
        token.isActivated(),
        token.getActivationExpiresAt(),
        token.getCreatedAt(),
        token.getCreatedBy(),
        token.getExpiresAt(),
        token.getSupersededAt(),
        token.getRevokedAt());
  }
}
