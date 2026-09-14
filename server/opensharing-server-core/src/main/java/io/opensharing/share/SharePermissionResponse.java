package io.opensharing.share;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.opensharing.http.AdminJson;
import java.time.Instant;

/** Names accompany the ids because permissions are managed by name. */
@AdminJson
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SharePermissionResponse(
    String shareId,
    String shareName,
    String recipientId,
    String recipientName,
    SharePrivilege privilege,
    Instant grantedAt,
    String grantedBy) {

  public static SharePermissionResponse from(SharePermissionEntity permission) {
    return new SharePermissionResponse(
        permission.getShare().getId(),
        permission.getShare().getName(),
        permission.getRecipient().getId(),
        permission.getRecipient().getName(),
        permission.getPrivilege(),
        permission.getCreatedAt(),
        permission.getGrantedBy());
  }
}
