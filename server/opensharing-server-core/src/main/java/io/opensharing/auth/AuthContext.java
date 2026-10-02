package io.opensharing.auth;

import java.util.Objects;

/**
 * Who a catalog call is made as. Every {@code CatalogConnector} method takes one so the catalog
 * can authenticate the caller and enforce its own permissions.
 *
 * @param serverId optional identity of the OpenSharing server itself, for catalogs that trust the
 *     server to act on a user's behalf
 * @param user the end-user the call is for; required, though any of its fields may be absent
 */
public record AuthContext(String serverId, UserContext user) {

  public AuthContext {
    Objects.requireNonNull(user, "user");
  }

  /** For embedded mode, where OpenSharing runs inside the catalog and needs no separate identity. */
  public static AuthContext of(UserContext user) {
    return new AuthContext(null, user);
  }
}
