package io.opensharing.share;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.opensharing.http.AdminJson;
import io.opensharing.asset.SharedDataObjectResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A share as the admin API reports it. {@code objects} is present on the single-share responses and
 * omitted from listings, which would otherwise read every share's contents to answer.
 */
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
    String updatedBy,
    List<SharedDataObjectResponse> objects) {

  public static ShareResponse from(ShareEntity share) {
    return of(share, null);
  }

  public static ShareResponse withObjects(
      ShareEntity share, List<SharedDataObjectResponse> objects) {
    return of(share, objects);
  }

  private static ShareResponse of(ShareEntity share, List<SharedDataObjectResponse> objects) {
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
        share.getUpdatedBy(),
        objects);
  }
}
