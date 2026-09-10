package io.opensharing.principal;

import io.opensharing.http.ApiException;

/**
 * The rule that a share or a recipient may only be changed by the principal that owns it. Reading is
 * open to any principal; only writes are held to the owner.
 *
 * <p>Ownership is compared by id — the catalog's own id for the caller's principal, for the {@code
 * unity} connector — so a principal of type {@code GROUP} owns objects only in its own right —
 * nothing resolves a user to the groups it belongs to yet. This is the one place to widen when that
 * arrives.
 */
public final class Ownership {

  private Ownership() {}

  /**
   * @param ownerId the id stored on the object being written, e.g. {@code ShareEntity#getOwnerId()}
   * @param what the object being written, phrased for the error message, e.g. {@code share 'sales'}
   */
  public static void requireOwner(String ownerId, Caller caller, String what) {
    if (!ownerId.equals(caller.principalId())) {
      throw ApiException.permissionDenied(
          "principal '" + caller.name() + "' does not own " + what);
    }
  }
}
