package io.opensharing.share;

import java.util.List;

/** Grants and revokes privileges for one or more recipients. Changes are applied in order. */
public record UpdateSharePermissionsRequest(List<Change> changes) {

  public UpdateSharePermissionsRequest {
    changes = changes == null ? List.of() : List.copyOf(changes);
  }

  /** Privileges to revoke from and grant to one recipient; removes are applied first. */
  public record Change(
      String recipientName, List<SharePrivilege> add, List<SharePrivilege> remove) {

    public Change {
      add = add == null ? List.of() : List.copyOf(add);
      remove = remove == null ? List.of() : List.copyOf(remove);
    }
  }
}
