package io.opensharing.share;

import io.opensharing.catalog.AssetType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/** PATCH body for share metadata and content changes. */
public record UpdateShareRequest(
    String displayName,
    String comment,
    Map<String, String> properties,
    List<@Valid Update> updates) {

  public UpdateShareRequest {
    updates = updates == null ? List.of() : List.copyOf(updates);
  }

  public record Update(@NotNull Action action, @NotNull @Valid DataObject dataObject) {}

  public record DataObject(String name, @NotNull AssetType type, String sharedAs) {}

  public enum Action {
    ADD,
    REMOVE
  }
}
