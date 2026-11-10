package io.opensharing.recipient;

import java.util.UUID;

/** The recipient a sharing protocol request's bearer token belongs to. */
public record RecipientPrincipal(UUID recipientId) {}
