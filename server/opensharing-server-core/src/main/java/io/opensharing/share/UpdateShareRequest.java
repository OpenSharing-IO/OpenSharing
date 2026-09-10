package io.opensharing.share;

import io.opensharing.catalog.AssetType;
import io.opensharing.http.AdminJson;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * Updates a share's metadata, its contents, or both. Only non-null metadata fields are applied, and
 * {@code updates} are applied in order.
 */
@AdminJson
public record UpdateShareRequest(
    String displayName,
    String comment,
    Map<String, String> properties,
    @Valid List<Update> updates) {

  public UpdateShareRequest {
    updates = updates == null ? List.of() : List.copyOf(updates);
  }

  /** One change to a share's contents. */
  @AdminJson
  public record Update(@NotNull Action action, @NotNull @Valid DataObject dataObject) {}

  public enum Action {
    ADD,
    REMOVE
  }

  /**
   * @param name the canonical catalog name, e.g. {@code main.sales.orders}
   * @param type defaults to {@code TABLE}. A {@code SCHEMA} shares every table the catalog puts in it,
   *     as it puts them there, so a provider need not list them.
   * @param sharedAs the two-level alias recipients see, defaulting to the last two levels of {@code
   *     name} — or, for a schema, the one-level name it appears as, defaulting to the last level. On a
   *     REMOVE it identifies the object when given, otherwise {@code name} does.
   */
  @AdminJson
  public record DataObject(@NotBlank String name, AssetType type, String sharedAs) {

    public DataObject {
      type = type == null ? AssetType.TABLE : type;
    }
  }
}
