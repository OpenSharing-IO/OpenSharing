package io.opensharing.recipient;

import io.opensharing.http.AdminJson;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Creating a recipient also mints its first token.
 *
 * @param tokenExpirationSeconds lifetime of that token, defaulting to the configured TTL
 * @param ipAccessList CIDR blocks the recipient may connect from; empty means anywhere
 */
@AdminJson
public record CreateRecipientRequest(
    @NotBlank String name,
    AuthType authType,
    Long tokenExpirationSeconds,
    List<String> ipAccessList,
    Map<String, String> properties) {

  public CreateRecipientRequest {
    authType = authType == null ? AuthType.TOKEN : authType;
    ipAccessList = ipAccessList == null ? List.of() : List.copyOf(ipAccessList);
  }

  Instant tokenExpiresAt() {
    return tokenExpirationSeconds == null
        ? null
        : Instant.now().plus(Duration.ofSeconds(tokenExpirationSeconds));
  }
}
