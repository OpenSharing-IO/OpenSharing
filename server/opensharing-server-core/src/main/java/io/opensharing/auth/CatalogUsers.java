package io.opensharing.auth;

import io.opensharing.catalog.CatalogConnector;
import io.opensharing.exception.CatalogException;
import io.opensharing.http.ApiException;

/** Checks users through the catalog on OpenSharing's behalf. */
public final class CatalogUsers {

  private CatalogUsers() {}

  /**
   * Confirms the catalog knows {@code userId} and it is active, so a typo cannot strand a share or
   * recipient under an owner who has left. A rejection means a bad {@code userId}.
   */
  public static void requireExisting(CatalogConnector catalog, String serverId, String userId) {
    try {
      catalog.authorize(
          new AuthContext(serverId, UserContext.fromUserIdAndName(userId, null)), null);
    } catch (CatalogException e) {
      throw ApiException.invalidParameter("owner '" + userId + "' is not a user of the catalog");
    }
  }
}
