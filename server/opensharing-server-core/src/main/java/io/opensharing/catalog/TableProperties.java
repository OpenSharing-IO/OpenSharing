package io.opensharing.catalog;

import java.util.Map;

/**
 * Table-only attributes of a {@link ResolvedAsset}.
 *
 * @param dataSourceFormat the format OpenSharing needs to serve the table, such as Delta
 * @param metadataLocation the table's current metadata file, for formats that are read from one,
 *     such as an Iceberg table's metadata JSON; null otherwise. It moves with every commit, so the
 *     catalog reports the current one each time it resolves the table
 * @param attributes any other attributes the catalog returns for the table, such as its table type
 */
public record TableProperties(
    DataSourceFormat dataSourceFormat, String metadataLocation, Map<String, String> attributes)
    implements AdditionalProperties {

  public TableProperties {
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }

  /** Properties of a table whose format is read without a metadata file, such as Delta. */
  public TableProperties(DataSourceFormat dataSourceFormat, Map<String, String> attributes) {
    this(dataSourceFormat, null, attributes);
  }

  @Override
  public AssetType type() {
    return AssetType.TABLE;
  }
}
