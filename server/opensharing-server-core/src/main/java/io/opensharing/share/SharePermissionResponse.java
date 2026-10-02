package io.opensharing.share;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** A privilege a recipient holds on a share. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SharePermissionResponse(
    String shareId,
    String shareName,
    String recipientId,
    String recipientName,
    SharePrivilege privilege,
    Instant grantedAt) {

  public static SharePermissionResponse from(SharePermissionEntity permission) {
    return new SharePermissionResponse(
        permission.getShare().getId(),
        permission.getShare().getName(),
        permission.getRecipient().getId(),
        permission.getRecipient().getName(),
        permission.getPrivilege(),
        permission.getCreatedAt());
  }
}
