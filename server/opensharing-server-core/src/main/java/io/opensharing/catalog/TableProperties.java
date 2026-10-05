package io.opensharing.catalog;

/**
 * Table-only attributes of a {@link ResolvedAsset}.
 *
 * @param subtype the catalog's finer-grained kind of table, such as {@code MANAGED} or {@code
 *     EXTERNAL}
 * @param dataSourceFormat the format OpenSharing needs to serve the table, such as Delta
 */
public record TableProperties(String subtype, DataSourceFormat dataSourceFormat)
    implements AdditionalProperties {

  @Override
  public AssetType type() {
    return AssetType.TABLE;
  }
}
