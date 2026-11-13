package io.opensharing.share;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.catalog.AssetType;
import java.util.List;
import java.util.Map;

/**
 * A share as the provider API reports it. Fields without a value are omitted, and {@code objects}
 * is omitted unless requested:
 *
 * <pre>{@code
 * {
 *   "id": "<share id>",
 *   "name": "<share name>",
 *   "displayName": "<display name>",
 *   "comment": "<comment>",
 *   "properties": {"<key>": "<value>"},
 *   "objects": [{"name": "<asset name>", "type": "TABLE", "sharedAs": "<alias>",
 *                "startVersion": 1}],
 *   "ownerId": "<owner user id>"
 * }
 * }</pre>
 *
 * <p>{@code ownerId} is the owner's catalog user id. The recipient protocol reports shares as
 * {@code ProtocolShare}, which has no owner.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShareResponse(
    String id,
    String name,
    String displayName,
    String comment,
    Map<String, String> properties,
    List<SharedDataObjectResponse> objects,
    String ownerId) {

  /** For the provider API: the share with its owner, without its objects. */
  public static ShareResponse from(ShareEntity share) {
    return from(share, null);
  }

  /** For the provider API: the share with its owner, and its objects when they are given. */
  public static ShareResponse from(ShareEntity share, List<SharedDataObjectEntity> objects) {
    return new ShareResponse(
        share.getId().toString(),
        share.getName(),
        share.getDisplayName(),
        share.getComment(),
        Map.copyOf(share.getProperties()),
        objects == null
            ? null
            : objects.stream().map(SharedDataObjectResponse::from).toList(),
        share.getOwnerId());
  }

  /** A catalog asset included in a share, with the alias recipients see it under. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record SharedDataObjectResponse(
      String name, AssetType type, String sharedAs, Long startVersion) {

    static SharedDataObjectResponse from(SharedDataObjectEntity object) {
      return new SharedDataObjectResponse(
          object.getFullName(),
          object.getType(),
          object.getSharedAs(),
          object.getStartVersion());
    }
  }
}
