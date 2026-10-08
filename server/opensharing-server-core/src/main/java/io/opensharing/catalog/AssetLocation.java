package io.opensharing.catalog;

import java.util.List;

/**
 * Where an asset's files live in storage.
 *
 * @param storageLocation the root location of the asset, such as {@code s3://bucket/path/}
 * @param auxiliaryLocations other storage locations that belong to the asset, which credentials
 *     may also be requested for; empty if none
 */
public record AssetLocation(String storageLocation, List<String> auxiliaryLocations) {

  public AssetLocation {
    auxiliaryLocations = auxiliaryLocations == null ? List.of() : List.copyOf(auxiliaryLocations);
  }
}
