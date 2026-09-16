package io.opensharing.share;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/** A share as the protocol reports it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShareResponse(
    String id,
    String name,
    String displayName,
    String comment,
    Map<String, String> properties) {

  public static ShareResponse from(ShareEntity share) {
    return new ShareResponse(
        share.getId(),
        share.getName(),
        share.getDisplayName(),
        share.getComment(),
        Map.copyOf(share.getProperties()));
  }
}
