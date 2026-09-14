package io.opensharing.share;

import io.opensharing.http.AdminJson;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/** Grants and revokes privileges for one or more recipients. Changes are applied in order. */
@AdminJson
public record UpdateSharePermissionsRequest(@Valid List<Change> changes) {

  public UpdateSharePermissionsRequest {
    changes = changes == null ? List.of() : List.copyOf(changes);
  }

  @AdminJson
  public record Change(
      @NotBlank String recipientName, List<SharePrivilege> add, List<SharePrivilege> remove) {

    public Change {
      add = add == null ? List.of() : List.copyOf(add);
      remove = remove == null ? List.of() : List.copyOf(remove);
    }
  }
}
