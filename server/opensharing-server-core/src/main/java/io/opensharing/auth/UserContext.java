package io.opensharing.auth;

import io.opensharing.http.ApiException;

/**
 * Who a catalog or provider request is for.
 *
 * @param id durable catalog id, stored as share/recipient owner
 * @param name display name
 */
public record UserContext(String id, String name) {

  public void requireOwner(String ownerId, String what) {
    if (!ownerId.equals(id)) {
      throw ApiException.permissionDenied("user '" + name + "' does not own " + what);
    }
  }
}
