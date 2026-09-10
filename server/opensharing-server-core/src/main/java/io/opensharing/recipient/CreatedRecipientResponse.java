package io.opensharing.recipient;

import io.opensharing.http.AdminJson;

/**
 * The answer to creating a recipient: the recipient itself and the token minted with it. This is the
 * only place that activation URL is ever shown, so deliver it to the recipient over a trusted
 * channel; a replacement can only be had by rotating.
 */
@AdminJson
public record CreatedRecipientResponse(RecipientResponse recipient, IssuedTokenResponse token) {

  public static CreatedRecipientResponse from(RecipientStore.NewRecipient created) {
    return new CreatedRecipientResponse(
        RecipientResponse.from(created.recipient()),
        IssuedTokenResponse.from(created.token().token(), created.token().activationUrl()));
  }
}
