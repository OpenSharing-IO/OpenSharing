package io.opensharing.catalog;

import java.util.Objects;

/**
 * An asset in the catalog's namespace, independent of any share alias.
 *
 * @param fullName the asset's full name in the catalog, such as {@code main.sales.orders}
 */
public record Asset(AssetType type, String fullName) {

  public Asset {
    Objects.requireNonNull(type, "type");
    if (fullName == null || fullName.isBlank()) {
      throw new IllegalArgumentException("asset's full name in the catalog must not be blank");
    }
  }
}
