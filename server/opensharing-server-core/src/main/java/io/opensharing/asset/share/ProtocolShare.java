package io.opensharing.asset.share;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.opensharing.share.ShareEntity;
import java.util.Map;

/**
 * A share as the recipient protocol reports it. Fields without a value are omitted:
 *
 * <pre>{@code
 * {
 *   "id": "<share id>",
 *   "name": "<share name>",
 *   "displayName": "<display name>",
 *   "comment": "<comment>",
 *   "properties": {"<key>": "<value>"}
 * }
 * }</pre>
 *
 * <p>A separate type from the provider API's {@code ShareResponse}, so provider-only fields such as
 * the owner cannot reach recipients.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProtocolShare(
    String id, String name, String displayName, String comment, Map<String, String> properties) {

  /** For the recipient protocol: the share as a recipient may see it, without the owner. */
  static ProtocolShare from(ShareEntity share) {
    return new ProtocolShare(
        share.getId().toString(),
        share.getName(),
        share.getDisplayName(),
        share.getComment(),
        Map.copyOf(share.getProperties()));
  }
}
