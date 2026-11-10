package io.opensharing.auth;

import io.opensharing.http.ApiException;

/** Ownership checks for objects OpenSharing stores. */
public final class Ownership {

  private Ownership() {}

  /** Whether {@code user} has the id {@code ownerId}; a missing id never matches. */
  public static boolean owns(UserContext user, String ownerId) {
    return user.userId() != null && user.userId().equals(ownerId);
  }

  /**
   * Checks a transfer of ownership to {@code newOwnerId} before it is applied. Only an admin may
   * transfer, and the transfer must be the request's only change, so nothing else rides in past
   * the owner. The admin flag is the one the caller's authentication produced: in the standalone
   * server that is {@code ProviderTokenDecorator}'s {@code authorize} result; an embedding host
   * delegates authentication to itself and sets the flag.
   */
  public static void checkAdminTransferOwner(
      UserContext user, String what, String newOwnerId, boolean otherChanges) {
    if (otherChanges) {
      throw ApiException.invalidParameter(
          "ownerId must be changed on its own, without other updates");
    }
    if (!user.admin()) {
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
