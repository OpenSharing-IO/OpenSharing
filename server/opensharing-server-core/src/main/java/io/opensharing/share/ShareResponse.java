package io.opensharing.share;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.catalog.AssetType;
import java.util.List;
import java.util.Map;

/**
 * A share as an API reports it. Fields without a value are omitted, and {@code objects} is omitted
 * unless requested:
 *
 * <pre>{@code
 * {
 *   "id": "<share id>",
 *   "name": "<share name>",
 *   "displayName": "<display name>",
 *   "comment": "<comment>",
 *   "properties": {"<key>": "<value>"},
 *   "objects": [{"name": "<asset name>", "type": "TABLE", "sharedAs": "<alias>"}],
 *   "owner": "<owner user id>"
 * }
 * }</pre>
 *
 * <p>{@code owner} is the owner's catalog user id. The provider API reports it; the recipient
 * protocol builds responses with {@link #from}, which leaves it out, because recipients never see
 * who owns a share.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShareResponse(
    String id,
    String name,
    String displayName,
    String comment,
    Map<String, String> properties,
    List<SharedDataObjectResponse> objects,
    String owner) {

  public static ShareResponse from(ShareEntity share) {
    return from(share, null);
  }

  public static ShareResponse from(ShareEntity share, List<SharedDataObjectEntity> objects) {
    return new ShareResponse(
        share.getId(),
        share.getName(),
        share.getDisplayName(),
        share.getComment(),
        Map.copyOf(share.getProperties()),
        objects == null
            ? null
            : objects.stream().map(SharedDataObjectResponse::from).toList(),
        null);
  }

  /** Like {@link #from(ShareEntity)}, with the owner, for the provider API. */
  public static ShareResponse fromOwned(ShareEntity share) {
    return fromOwned(share, null);
  }

  public static ShareResponse fromOwned(ShareEntity share, List<SharedDataObjectEntity> objects) {
    return new ShareResponse(
        share.getId(),
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
  public record SharedDataObjectResponse(String name, AssetType type, String sharedAs) {

    static SharedDataObjectResponse from(SharedDataObjectEntity object) {
      return new SharedDataObjectResponse(
          object.getFullName(), object.getType(), object.getSharedAs());
    }
  }
}
