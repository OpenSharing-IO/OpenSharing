package io.opensharing.share;

import io.opensharing.catalog.AssetType;
import java.util.List;
import java.util.Map;

/**
 * PATCH body for share metadata and content changes. Null fields are left unchanged; {@code
 * updates} adds or removes objects in order.
 */
public record UpdateShareRequest(
    String displayName,
    String comment,
    Map<String, String> properties,
    List<Update> updates) {

  public UpdateShareRequest {
    updates = updates == null ? List.of() : List.copyOf(updates);
  }

  /** One object to add to or remove from the share. */
  public record Update(Action action, DataObject dataObject) {}

  /**
   * A catalog object. {@code name} is the full catalog name; {@code sharedAs} is the alias
   * recipients see, {@code schema} or {@code schema.table}, and defaults to {@code name} without
   * its catalog. A remove needs {@code name} or {@code sharedAs}.
   */
  public record DataObject(String name, AssetType type, String sharedAs) {}

  public enum Action {
    ADD,
    REMOVE
  }
}
