package io.opensharing.share;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Grants and revokes privileges on one share for one or more recipients. Changes are applied in
 * order, and either all of them take effect or none do.
 *
 * <pre>{@code
 * PATCH {provider base path}/shares/<share name>/permissions
 * {
 *   "changes": [
 *     {"recipientName": "<recipient name>", "add": ["SELECT"]},
 *     {"recipientName": "<recipient name>", "remove": ["SELECT"]}
 *   ]
 * }
 * }</pre>
 */
public record UpdateSharePermissionsRequest(List<Change> changes) {

  public UpdateSharePermissionsRequest {
    changes = changes == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(changes));
  }

  /** Privileges to revoke from and grant to one recipient; removes are applied first. */
  public record Change(
      String recipientName, List<SharePrivilege> add, List<SharePrivilege> remove) {

    public Change {
      add = add == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(add));
      remove = remove == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(remove));
    }
  }
}
