package io.opensharing.recipient;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.opensharing.http.AdminJson;
import java.time.Instant;

/**
 * The activation URL is the only place the bearer token is ever disclosed, and only on first use.
 * Deliver it to the recipient over a trusted channel.
 */
@AdminJson
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IssuedTokenResponse(
    String tokenId,
    String recipientName,
    String activationUrl,
    Instant activationExpiresAt,
    Instant expiresAt) {

  public static IssuedTokenResponse from(RecipientTokenEntity token, String activationUrl) {
    return new IssuedTokenResponse(
        token.getId(),
        token.getRecipient().getName(),
        activationUrl,
        token.getActivationExpiresAt(),
        token.getExpiresAt());
  }
}
