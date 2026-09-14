package io.opensharing.recipient;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.opensharing.http.AdminJson;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A recipient as the admin API reports it, with the lifecycle of each of its tokens. */
@AdminJson
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecipientResponse(
    String recipientId,
    String name,
    AuthType authType,
    String ownerId,
    List<String> ipAccessList,
    Map<String, String> properties,
    Instant createdAt,
    String createdBy,
    Instant updatedAt,
    String updatedBy,
    List<TokenResponse> tokens) {

  public static RecipientResponse from(RecipientEntity recipient) {
    return of(recipient, null);
  }

  public static RecipientResponse withTokens(
      RecipientEntity recipient, List<TokenResponse> tokens) {
    return of(recipient, tokens);
  }

  private static RecipientResponse of(RecipientEntity recipient, List<TokenResponse> tokens) {
    return new RecipientResponse(
        recipient.getId(),
        recipient.getName(),
        recipient.getAuthType(),
        recipient.getOwnerId(),
        List.copyOf(recipient.getIpAccessList()),
        Map.copyOf(recipient.getProperties()),
        recipient.getCreatedAt(),
        recipient.getCreatedBy(),
        recipient.getUpdatedAt(),
        recipient.getUpdatedBy(),
        tokens);
  }
}
