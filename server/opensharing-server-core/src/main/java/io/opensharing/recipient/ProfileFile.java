package io.opensharing.recipient;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Delta Sharing profile file returned when a recipient redeems an activation URL. Clients read
 * {@code endpoint} and {@code bearerToken} to call the sharing protocol; {@code expirationTime} is
 * omitted when the token never expires.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProfileFile(
    int shareCredentialsVersion, String bearerToken, String endpoint, String expirationTime) {}
