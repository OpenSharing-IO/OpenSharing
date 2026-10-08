package io.opensharing.recipient;

/**
 * POST body to create a recipient. The authenticated caller becomes the owner. Only {@code TOKEN}
 * authentication is accepted for now.
 */
public record CreateRecipientRequest(
    String name, String comment, AuthenticationType authenticationType) {}
