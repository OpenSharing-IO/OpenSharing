package io.opensharing.catalog;

import java.util.Map;

/**
 * Table-only attributes of a {@link ResolvedAsset}.
 *
 * @param dataSourceFormat the format OpenSharing needs to serve the table, such as Delta
 * @param attributes any other attributes the catalog returns for the table, such as its table type
 */
public record TableProperties(DataSourceFormat dataSourceFormat, Map<String, String> attributes)
    implements AdditionalProperties {

  public TableProperties {
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }

  @Override
  public AssetType type() {
    return AssetType.TABLE;
  }

  /**
   * Whether the table may be read as of a version or timestamp, and its Change Data Feed served.
   * Catalogs record it as the table's {@code historyShared} attribute; sharing a table's history
   * is opt-in, so only an explicit {@code false} disables it.
   */
  public boolean historyShared() {
    return !"false".equalsIgnoreCase(attributes.get("historyShared"));
  }
}
