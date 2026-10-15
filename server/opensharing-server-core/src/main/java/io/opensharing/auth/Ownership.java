package io.opensharing.auth;

import io.opensharing.http.ApiException;

/** Ownership checks for objects OpenSharing stores. */
public final class Ownership {

  private Ownership() {}

  /** Whether {@code user} has the id {@code ownerId}; a missing id never matches. */
  public static boolean owns(UserContext user, String ownerId) {
    return user.userId() != null && user.userId().equals(ownerId);
  }

  /** Passes only when {@link #owns} does. */
  public static void check(UserContext user, String ownerId, String what) {
    if (!owns(user, ownerId)) {
      throw ApiException.permissionDenied("user '" + user.userName() + "' does not own " + what);
    }
  }
}
