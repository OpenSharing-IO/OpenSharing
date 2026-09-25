package io.opensharing.auth;

import io.opensharing.http.ApiException;

/**
 * End-user identity for a catalog or provider request. Every field is optional.
 *
 * @param userId durable catalog id, stored as share/recipient owner when present
 * @param bearerToken user credential, when the catalog should authenticate or act as this user
 * @param userName display name
 */
public record UserContext(String userId, String bearerToken, String userName) {

  public UserContext(String userId, String userName) {
    this(userId, null, userName);
  }

  public void requireOwner(String ownerId, String what) {
    if (userId == null || !ownerId.equals(userId)) {
      throw ApiException.permissionDenied("user '" + userName + "' does not own " + what);
    }
  }
}
