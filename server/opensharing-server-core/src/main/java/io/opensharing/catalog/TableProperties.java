package io.opensharing.catalog;

import java.util.List;
import java.util.Map;

/**
 * Table-only attributes of a {@link ResolvedAsset}.
 *
 * <p>For an Iceberg table, the catalog also returns what a recipient needs to read it: the current
 * metadata, and read credentials when {@link ResolveOptions#readCredentials()} asks for them.
 *
 * @param dataSourceFormat the table's format, such as Delta or Iceberg
 * @param metadataLocation where an Iceberg table's current metadata JSON is, which changes with
 *     every commit; null for other formats
 * @param metadata the metadata JSON at {@code metadataLocation}, unmodified; null for other formats
 * @param readCredentials credentials to read an Iceberg table's storage location; empty unless
 *     requested
 * @param attributes other attributes the catalog returns, such as the table type
 */
public record TableProperties(
    DataSourceFormat dataSourceFormat,
    String metadataLocation,
    String metadata,
    List<StorageCredentials> readCredentials,
    Map<String, String> attributes)
    implements AdditionalProperties {

  public TableProperties {
    readCredentials = readCredentials == null ? List.of() : List.copyOf(readCredentials);
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }

  /** Properties of a table with no metadata or credentials, such as a Delta table. */
  public TableProperties(DataSourceFormat dataSourceFormat, Map<String, String> attributes) {
    this(dataSourceFormat, null, null, List.of(), attributes);
  }

  @Override
  public AssetType type() {
    return AssetType.TABLE;
  }

  /** Omits the metadata, which can be large. The credentials print without their secrets. */
  @Override
  public String toString() {
    return "TableProperties[dataSourceFormat="
        + dataSourceFormat
        + ", metadataLocation="
        + metadataLocation
        + ", readCredentials="
        + readCredentials
        + ", attributes="
        + attributes
        + "]";
  }
}
