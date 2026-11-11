package io.opensharing.recipient;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A recipient as the provider API reports it. {@code activationUrl} is the link the provider sends
 * to the recipient; omitted once the code has been redeemed.
 *
 * <pre>{@code
 * {
 *   "id": "<recipient id>",
 *   "name": "<recipient name>",
 *   "comment": "<comment>",
 *   "authenticationType": "TOKEN",
 *   "activationUrl": "<activation URL>",
 *   "ownerId": "<owner user id>"
 * }
 * }</pre>
 *
 * <p>{@code ownerId} is the owner's catalog user id. Only the provider API returns recipients;
 * recipients themselves never receive this type.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecipientResponse(
    String id,
    String name,
    String comment,
    AuthenticationType authenticationType,
    String activationUrl,
    String ownerId) {

  /** For the provider API: the recipient with its owner and, until redeemed, activation URL. */
  public static RecipientResponse from(RecipientEntity recipient, String activationUrl) {
    return new RecipientResponse(
        recipient.getId().toString(),
        recipient.getName(),
        recipient.getComment(),
        recipient.getAuthenticationType(),
        activationUrl,
        recipient.getOwnerId());
  }
}
