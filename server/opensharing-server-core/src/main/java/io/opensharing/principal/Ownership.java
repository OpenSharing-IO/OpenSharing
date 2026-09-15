package io.opensharing.principal;

import io.opensharing.http.ApiException;

/**
 * A share may only be changed by the principal that owns it. Reading is open to any authenticated
 * principal.
 */
public final class Ownership {

  private Ownership() {}

  public static void requireOwner(String ownerId, Caller caller, String what) {
    if (!ownerId.equals(caller.principalId())) {
      throw ApiException.permissionDenied(
          "principal '" + caller.name() + "' does not own " + what);
    }
  }
}
