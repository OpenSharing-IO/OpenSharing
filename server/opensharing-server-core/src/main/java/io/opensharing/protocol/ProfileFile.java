package io.opensharing.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The {@code config.share} profile file a recipient downloads from an activation URL.
 *
 * @param expirationTime ISO-8601 instant, or null when the token never expires
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProfileFile(
    int shareCredentialsVersion,
    String endpoint,
    String icebergEndpoint,
    String bearerToken,
    String expirationTime) {

  public static final int CURRENT_VERSION = 1;
}
