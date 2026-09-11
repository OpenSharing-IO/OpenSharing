package io.opensharing.catalog;

import java.util.Objects;

/** An asset in the catalog's namespace, independent of any share alias. */
public record AssetLookup(AssetType type, String identifier) {

  public AssetLookup {
    Objects.requireNonNull(type, "type");
    if (identifier == null || identifier.isBlank()) {
      throw new IllegalArgumentException("catalog identifier must not be blank");
    }
  }

  public static AssetLookup of(AssetType type, String identifier) {
    return new AssetLookup(type, identifier);
  }
}
