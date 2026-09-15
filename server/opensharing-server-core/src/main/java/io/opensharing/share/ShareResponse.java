package io.opensharing.share;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/** A share as the provider API reports it. */
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
    Instant updatedAt) {

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
        share.getUpdatedAt());
  }
}
