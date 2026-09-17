package io.opensharing.recipient;

import jakarta.validation.constraints.NotBlank;

/** POST body to create a recipient. The authenticated caller becomes the owner. */
public record CreateRecipientRequest(
    @NotBlank String name, String comment, AuthenticationType authenticationType) {}
