package io.opensharing.auth;

import io.opensharing.http.ApiException;

/** Rejects updates unless the authenticated user owns the share. */
public final class Ownership {

  private Ownership() {}

  public static void requireOwner(String ownerId, UserContext user, String what) {
    if (!ownerId.equals(user.id())) {
      throw ApiException.permissionDenied(
          "user '" + user.name() + "' does not own " + what);
    }
  }
}
