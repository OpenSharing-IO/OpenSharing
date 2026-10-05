package io.opensharing.recipient;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * POST body to create a recipient. The authenticated caller becomes the owner. Only {@code TOKEN}
 * authentication is accepted for now.
 */
public record CreateRecipientRequest(
    @NotBlank String name, String comment, @NotNull AuthenticationType authenticationType) {}
