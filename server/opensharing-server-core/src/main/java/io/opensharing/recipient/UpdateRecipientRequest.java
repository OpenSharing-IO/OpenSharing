package io.opensharing.recipient;

/**
 * PATCH body for recipient metadata. Null fields are left unchanged.
 *
 * <p>{@code owner} transfers the recipient to that catalog user id. Only an admin of the catalog
 * may set it, and only in a request whose owner is the sole change unless the caller also owns the
 * recipient.
 */
public record UpdateRecipientRequest(String comment, String owner) {}
