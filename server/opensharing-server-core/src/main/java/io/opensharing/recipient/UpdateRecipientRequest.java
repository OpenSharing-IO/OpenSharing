package io.opensharing.recipient;

/**
 * PATCH body for recipient metadata. Null fields are left unchanged.
 *
 * <p>{@code ownerId} transfers the recipient to that catalog user id, in a request that carries no
 * other change. Only an admin of the catalog may set it.
 */
public record UpdateRecipientRequest(String comment, String ownerId) {}
