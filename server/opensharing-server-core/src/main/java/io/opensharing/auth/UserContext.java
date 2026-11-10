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
 * @param admin whether the catalog says this user is an admin of it, who may transfer the
 *     ownership of any share. Only the value a {@code CatalogConnector#authorize} returned for the
 *     current request may be trusted; it is never taken from a request body or stored data
 */
public record UserContext(String userId, String userAuthToken, String userName, boolean admin) {

  public UserContext(String userId, String userAuthToken, String userName) {
    this(userId, userAuthToken, userName, false);
  }

  public static UserContext fromUserIdAndName(String userId, String userName) {
    return new UserContext(userId, null, userName);
  }

  /** A copy that says the catalog calls this user an admin, keeping the token. */
  public UserContext asAdmin() {
    return new UserContext(userId, userAuthToken, userName, true);
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
        + ", admin="
        + admin
        + "]";
  }
}
