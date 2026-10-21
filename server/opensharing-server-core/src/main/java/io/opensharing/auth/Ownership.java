package io.opensharing.auth;

import io.opensharing.http.ApiException;

/** Ownership checks for objects OpenSharing stores. */
public final class Ownership {

  private Ownership() {}

  /** Passes only when {@code user} has the id {@code ownerId}; a missing id never matches. */
  public static void check(UserContext user, String ownerId, String what) {
    if (user.userId() == null || ownerId == null || !ownerId.equals(user.userId())) {
      throw ApiException.permissionDenied("user '" + user.userName() + "' does not own " + what);
    }
  }
}
