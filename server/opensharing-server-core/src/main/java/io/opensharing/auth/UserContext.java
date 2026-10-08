package io.opensharing.auth;

/**
 * End-user identity for a catalog call.
 *
 * <p>A user is known by token or by id, depending on the request. When the user calls OpenSharing
 * directly, the request carries their catalog token; the catalog authenticates it and returns the
 * user's id. OpenSharing keeps only that id, never the token, so later calls made for the same
 * user without them present (such as serving a recipient request) identify the user by id alone.
 *
 * @param userId the catalog's durable id for the user
 * @param userAuthToken the user's catalog token, present only while serving that user's own
 *     request
 * @param userName display name
 */
public record UserContext(String userId, String userAuthToken, String userName) {

  public static UserContext fromUserIdAndName(String userId, String userName) {
    return new UserContext(userId, null, userName);
  }

  /** Hides the token, which is a live catalog credential and must never be logged. */
  @Override
  public String toString() {
    return "UserContext[userId="
        + userId
        + ", userAuthToken="
        + (userAuthToken == null ? null : "***")
        + ", userName="
        + userName
        + "]";
  }
}
