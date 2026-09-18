package io.opensharing.share;

/** A recipient granted access to a share. */
public record ShareRecipientResponse(String name) {

  public static ShareRecipientResponse from(ShareRecipientEntity grant) {
    return new ShareRecipientResponse(grant.getRecipient().getName());
  }
}
