package io.opensharing.asset;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.opensharing.catalog.AccessMode;
import io.opensharing.catalog.AssetType;
import io.opensharing.http.AdminJson;
import java.time.Instant;
import java.util.List;

/**
 * A shared object as the admin API reports it. {@code storageLocation} and {@code accessModes} are
 * what the catalog last reported, included so an admin can see where an object actually points.
 */
@AdminJson
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SharedDataObjectResponse(
    String sharedObjectId,
    String shareId,
    String sourceAssetId,
    String name,
    AssetType type,
    String sourceSubtype,
    String sourceFormat,
    String sharedAs,
    SharedObjectStatus status,
    String storageLocation,
    List<String> accessModes,
    Instant addedAt,
    String addedBy,
    Instant updatedAt,
    String updatedBy) {

  public static SharedDataObjectResponse from(SharedDataObjectEntity object) {
    return new SharedDataObjectResponse(
        object.getId(),
        object.getShare().getId(),
        object.getSourceAssetId(),
        object.getName(),
        object.getType(),
        object.getSourceSubtype(),
        object.getSourceFormat() == null ? null : object.getSourceFormat().wireName(),
        object.getSharedAs(),
        object.getStatus(),
        object.getStorageLocation(),
        object.getAccessModes().stream().map(AccessMode::wireName).sorted().toList(),
        object.getCreatedAt(),
        object.getAddedBy(),
        object.getUpdatedAt(),
        object.getUpdatedBy());
  }
}
