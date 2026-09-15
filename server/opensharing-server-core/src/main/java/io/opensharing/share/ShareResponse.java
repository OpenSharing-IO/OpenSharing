package io.opensharing.share;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.opensharing.http.AdminJson;
import java.time.Instant;
import java.util.Map;

/** A share as the admin API reports it. */
@AdminJson
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShareResponse(
    String shareId,
    String name,
    String displayName,
    String comment,
    Map<String, String> properties,
    String ownerId,
    Instant createdAt,
    String createdBy,
    Instant updatedAt,
    String updatedBy) {

  public static ShareResponse from(ShareEntity share) {
    return new ShareResponse(
        share.getId(),
        share.getName(),
        share.getDisplayName(),
        share.getComment(),
        Map.copyOf(share.getProperties()),
        share.getOwnerId(),
        share.getCreatedAt(),
        share.getCreatedBy(),
        share.getUpdatedAt(),
        share.getUpdatedBy());
  }
}
