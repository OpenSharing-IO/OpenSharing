package io.opensharing.share;

import jakarta.validation.constraints.NotBlank;

/** POST body to grant a recipient access to a share. */
public record GrantShareRecipientRequest(@NotBlank String name) {}
