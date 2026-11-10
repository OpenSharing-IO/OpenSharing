package io.opensharing.auth;

import io.opensharing.catalog.CatalogConnector;
import io.opensharing.exception.CatalogException;
import io.opensharing.http.ApiException;

/** Ownership checks for objects OpenSharing stores. */
public final class Ownership {

  private Ownership() {}

  /** Whether {@code user} has the id {@code ownerId}; a missing id never matches. */
  public static boolean owns(UserContext user, String ownerId) {
    return user.userId() != null && user.userId().equals(ownerId);
  }

  /**
   * Checks an update to an object {@code ownerId} owns, before any of it is applied, and returns
   * whether the catalog calls the caller an admin. Setting {@code newOwner} transfers the object:
   * it must be the request's only change, needs an admin of the catalog, and must name a user the
   * catalog knows, so a typo can't strand the object. Any other update needs the owner. {@code
   * what} names the object in errors, such as {@code share 'sales'}.
   */
  public static boolean checkUpdate(
      CatalogConnector catalog,
      AuthContext caller,
      String what,
      String ownerId,
      String newOwner,
      boolean otherChanges) {
    // The catalog decides per call whether the caller is an admin; OpenSharing never takes that
    // from the request body or from stored data.
    boolean admin = catalog.authorize(caller, null).admin();
    if (newOwner == null) {
      check(caller.user(), ownerId, what);
      return admin;
    }
    if (otherChanges) {
      throw ApiException.invalidParameter(
          "owner must be changed on its own, without other updates");
    }
    if (!admin) {
      throw ApiException.permissionDenied(
          "only an admin of the catalog may change the owner of " + what);
    }
    if (newOwner.isBlank()) {
      throw ApiException.invalidParameter("owner must not be blank");
    }
    if (!newOwner.equals(ownerId)) {
      try {
        catalog.authorize(
            new AuthContext(caller.serverId(), UserContext.fromUserIdAndName(newOwner, null)),
            null);
      } catch (CatalogException e) {
        throw ApiException.invalidParameter(
            "owner '" + newOwner + "' is not a user of the catalog");
      }
    }
    return admin;
  }

  /** Passes only when {@link #owns} does. */
  public static void check(UserContext user, String ownerId, String what) {
    if (!owns(user, ownerId)) {
      throw ApiException.permissionDenied("user '" + user.userName() + "' does not own " + what);
    }
  }
}
