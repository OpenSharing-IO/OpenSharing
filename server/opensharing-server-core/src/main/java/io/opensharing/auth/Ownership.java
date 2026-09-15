package io.opensharing.auth;

import io.opensharing.http.ApiException;

/** Rejects updates unless the caller owns the share. Reads are allowed for any authenticated caller. */
public final class Ownership {

  private Ownership() {}

  public static void requireOwner(String ownerId, Caller caller, String what) {
    if (!ownerId.equals(caller.id())) {
      throw ApiException.permissionDenied(
          "principal '" + caller.name() + "' does not own " + what);
    }
  }
}
