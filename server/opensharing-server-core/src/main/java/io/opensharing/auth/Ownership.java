package io.opensharing.auth;

import io.opensharing.catalog.CatalogConnector;
import io.opensharing.http.ApiException;

/** Ownership checks for objects OpenSharing stores. */
public final class Ownership {

  private Ownership() {}

  /** Whether {@code user} has the id {@code ownerId}; a missing id never matches. */
  public static boolean owns(UserContext user, String ownerId) {
    return user.userId() != null && user.userId().equals(ownerId);
  }

  /**
   * Checks a transfer of ownership to {@code newOwnerId} before it is applied. Only an admin of
   * the catalog, as {@code authorize} reports for this call, may transfer, and the transfer must
   * be the request's only change, so nothing else rides in past the owner.
   */
  public static void checkAdminTransferOwner(
      CatalogConnector catalog,
      AuthContext caller,
      String what,
      String newOwnerId,
      boolean otherChanges) {
    if (otherChanges) {
      throw ApiException.invalidParameter(
          "ownerId must be changed on its own, without other updates");
    }
    // Only the catalog's answer for this call says who is an admin.
    if (!catalog.authorize(caller, null).admin()) {
      throw ApiException.permissionDenied(
          "only an admin of the catalog may change the owner of " + what);
    }
    if (newOwnerId == null || newOwnerId.isBlank()) {
      throw ApiException.invalidParameter("ownerId must not be blank");
    }
  }

  /** Passes only when {@link #owns} does. */
  public static void check(UserContext user, String ownerId, String what) {
    if (!owns(user, ownerId)) {
      throw ApiException.permissionDenied("user '" + user.userName() + "' does not own " + what);
    }
  }
}
