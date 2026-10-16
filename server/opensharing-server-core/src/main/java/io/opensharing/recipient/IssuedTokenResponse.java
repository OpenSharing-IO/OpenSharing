package io.opensharing.recipient;

import java.time.Instant;

/**
 * Pending replacement token returned by the provider rotation API. The provider sends {@code
 * activationUrl} to the recipient, who redeems it for a new profile.
 *
 * <pre>{@code
 * {
 *   "tokenId": "<token id>",
 *   "recipientName": "<recipient name>",
 *   "activationUrl": "<activation URL>",
 *   "expiresAt": "<ISO-8601 instant>"
 * }
 * }</pre>
 */
public record IssuedTokenResponse(
    String tokenId, String recipientName, String activationUrl, Instant expiresAt) {

  static IssuedTokenResponse from(RecipientTokenEntity token, String activationUrl) {
    return new IssuedTokenResponse(
        token.getId(), token.getRecipient().getName(), activationUrl, token.getExpiresAt());
  }
}
