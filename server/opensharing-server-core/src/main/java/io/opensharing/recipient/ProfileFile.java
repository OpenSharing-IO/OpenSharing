package io.opensharing.recipient;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Protocol profile returned when a recipient redeems an activation URL. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProfileFile(
    int shareCredentialsVersion, String bearerToken, String endpoint, String expirationTime) {}
