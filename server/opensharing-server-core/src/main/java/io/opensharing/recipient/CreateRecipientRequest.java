package io.opensharing.recipient;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** POST body to create a recipient. The authenticated caller becomes the owner. */
public record CreateRecipientRequest(
    @NotBlank String name, String comment, @NotNull AuthenticationType authenticationType) {}
