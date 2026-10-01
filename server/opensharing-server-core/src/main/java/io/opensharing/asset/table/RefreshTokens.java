package io.opensharing.asset.table;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opensharing.http.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** Encodes snapshot refresh tokens so a later query can resign URLs for the same version. */
public final class RefreshTokens {

  private static final String PREFIX = "os1rt:";
  private static final ObjectMapper JSON =
      new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

  private RefreshTokens() {}

  public static String encode(String tableId, long version, Instant expiration) {
    try {
      String payload =
          JSON.writeValueAsString(new Payload(tableId, version, expiration.toEpochMilli()));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString((PREFIX + payload).getBytes(StandardCharsets.UTF_8));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("failed to encode refresh token", e);
    }
  }

  public static long versionOf(String token, String tableId) {
    Payload payload = decode(token);
    if (payload.expirationTimestamp() < Instant.now().toEpochMilli()) {
      throw ApiException.invalidParameter(
          "The refresh token has expired. Please restart the query.");
    }
    if (!tableId.equals(payload.id())) {
      throw ApiException.invalidParameter(
          "The table specified in the refresh token does not match the table being queried.");
    }
    return payload.version();
  }

  private static Payload decode(String token) {
    try {
      String decoded =
          new String(Base64.getUrlDecoder().decode(token.trim()), StandardCharsets.UTF_8);
      if (!decoded.startsWith(PREFIX)) {
        throw new IllegalArgumentException("unexpected refresh token");
      }
      return JSON.readValue(decoded.substring(PREFIX.length()), Payload.class);
    } catch (Exception invalid) {
      throw ApiException.invalidParameter("Error decoding refresh token: " + token);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record Payload(String id, long version, long expirationTimestamp) {}
}
