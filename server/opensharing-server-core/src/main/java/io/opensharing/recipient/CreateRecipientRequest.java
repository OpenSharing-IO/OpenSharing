package io.opensharing.recipient;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * POST body to create a recipient. The authenticated caller becomes the owner. Only {@code TOKEN}
 * authentication is accepted for now.
 *
 * @param tokenExpirationDays first-token lifetime in days; defaults to the configured token TTL
 */
public record CreateRecipientRequest(
    @NotBlank String name,
    String comment,
    @NotNull AuthenticationType authenticationType,
    @Positive Long tokenExpirationDays) {}
