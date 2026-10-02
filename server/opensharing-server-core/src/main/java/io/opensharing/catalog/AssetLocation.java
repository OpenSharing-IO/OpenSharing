package io.opensharing.catalog;

import java.util.List;

/**
 * Where an asset's files live in storage.
 *
 * @param storageLocation the root location of the asset's data, such as {@code s3://bucket/path/}
 * @param metadataLocation the absolute path of the asset's current metadata file, for formats whose
 *     catalog tracks one, such as an Iceberg table's {@code
 *     s3://bucket/path/metadata/00003-<uuid>.metadata.json}; optional
 * @param auxiliaryLocations other storage locations that belong to the asset, which credentials
 *     may also be requested for; empty if none
 */
public record AssetLocation(
    String storageLocation, String metadataLocation, List<String> auxiliaryLocations) {

  public AssetLocation {
    auxiliaryLocations = auxiliaryLocations == null ? List.of() : List.copyOf(auxiliaryLocations);
  }
}
