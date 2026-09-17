package io.opensharing.share;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.catalog.AssetType;
import java.util.List;
import java.util.Map;

/** A share as the protocol reports it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShareResponse(
    String id,
    String name,
    String displayName,
    String comment,
    Map<String, String> properties,
    List<SharedDataObjectResponse> objects) {

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
            : objects.stream().map(SharedDataObjectResponse::from).toList());
  }

  /** A catalog asset included in a share. */
  public record SharedDataObjectResponse(String name, AssetType type, String sharedAs) {

    static SharedDataObjectResponse from(SharedDataObjectEntity object) {
      return new SharedDataObjectResponse(object.getName(), object.getType(), object.getSharedAs());
    }
  }
}
