package io.opensharing.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Closes a Delta read response, so a client can tell a complete stream from a truncated one. Sent
 * only when the client asks for it through the {@code delta-sharing-capabilities} header.
 *
 * @param minUrlExpirationTimestamp the earliest expiry among the urls in this response, which tells
 *     a client when it must ask again
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EndStreamAction(
    String refreshToken, String nextPageToken, Long minUrlExpirationTimestamp) {}
