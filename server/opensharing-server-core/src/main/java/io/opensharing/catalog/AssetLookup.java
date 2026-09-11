package io.opensharing.catalog;

import java.util.Objects;

/**
 * Identifies an asset in the catalog's own namespace, independent of how it is named in a share.
 *
 * @param identifier catalog-native identifier, e.g. a three-level {@code main.sales.orders}
 */
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
