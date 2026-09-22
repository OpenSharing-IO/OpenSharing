package io.opensharing.auth;

/**
 * Who a catalog call is made as.
 *
 * @param serverId optional identity of the OpenSharing server itself
 * @param user optional end-user; any of its fields may also be absent
 */
public record AuthContext(String serverId, UserContext user) {

  public static AuthContext of(UserContext user) {
    return new AuthContext(null, user);
  }
}
