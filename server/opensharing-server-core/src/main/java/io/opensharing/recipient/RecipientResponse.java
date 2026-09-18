package io.opensharing.recipient;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A recipient as the provider API reports it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecipientResponse(
    String id,
    String name,
    String comment,
    AuthenticationType authenticationType,
    String activationUrl) {

  public static RecipientResponse from(RecipientEntity recipient, String activationUrl) {
    return new RecipientResponse(
        recipient.getId(),
        recipient.getName(),
        recipient.getComment(),
        recipient.getAuthenticationType(),
        activationUrl);
  }
}
