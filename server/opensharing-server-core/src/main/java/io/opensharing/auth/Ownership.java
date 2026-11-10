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
   * Checks an update before it is applied and returns whether the caller is a catalog admin. A
   * transfer to {@code newOwnerId} must be the only change and needs an admin; anything else needs
   * the owner.
   */
  public static boolean authorizeUpdate(
      CatalogConnector catalog,
      AuthContext caller,
      String what,
      String ownerId,
      String newOwnerId,
      boolean otherChanges) {
    // Only the catalog's answer for this call says who is an admin.
    boolean admin = catalog.authorize(caller, null).admin();
    if (newOwnerId == null) {
      check(caller.user(), ownerId, what);
      return admin;
    }
    if (otherChanges) {
      throw ApiException.invalidParameter(
          "ownerId must be changed on its own, without other updates");
    }
    if (!admin) {
      throw ApiException.permissionDenied(
          "only an admin of the catalog may change the owner of " + what);
    }
    if (newOwnerId.isBlank()) {
      throw ApiException.invalidParameter("ownerId must not be blank");
    }
    if (!newOwnerId.equals(ownerId)) {
      try {
        catalog.authorize(
            new AuthContext(caller.serverId(), UserContext.fromUserIdAndName(newOwnerId, null)),
            null);
      } catch (CatalogException e) {
        throw ApiException.invalidParameter(
            "owner '" + newOwnerId + "' is not a user of the catalog");
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
