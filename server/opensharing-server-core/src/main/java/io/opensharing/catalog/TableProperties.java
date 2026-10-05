package io.opensharing.catalog;

/**
 * Table-only attributes of a {@link ResolvedAsset}.
 *
 * @param subtype the catalog's kind of table; null when the catalog does not report one
 * @param dataSourceFormat the format OpenSharing needs to serve the table, such as Delta
 */
public record TableProperties(TableSubtype subtype, DataSourceFormat dataSourceFormat)
    implements AdditionalProperties {

  @Override
  public AssetType type() {
    return AssetType.TABLE;
  }
}
