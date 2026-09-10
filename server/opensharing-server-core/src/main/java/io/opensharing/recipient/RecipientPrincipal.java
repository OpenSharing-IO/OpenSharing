package io.opensharing.recipient;


/** The authenticated recipient behind a protocol request. */
public record RecipientPrincipal(String recipientId) {

  public static final String REQUEST_ATTRIBUTE = "io.opensharing.recipient";

  public static RecipientPrincipal of(RecipientEntity recipient) {
    return new RecipientPrincipal(recipient.getId());
  }
}
