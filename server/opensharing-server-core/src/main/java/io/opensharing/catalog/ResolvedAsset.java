package io.opensharing.catalog;

import java.util.Objects;

/**
 * What the catalog knows about an asset, as returned by {@link CatalogConnector#resolveAsset} and
 * {@link CatalogConnector#listChildren}: its identity, where its data lives, and for tables, the
 * format OpenSharing needs to serve them.
 *
 * @param type the asset's type
 * @param subtype the catalog's finer-grained kind within {@code type}, for any asset type, such as
 *     {@code MANAGED} or {@code EXTERNAL} for a table
 * @param fullName the asset's full name in the catalog, such as {@code main.sales.orders}
 * @param catalogAssetId the catalog's own stable id for the asset
 * @param location where the asset's files live in storage
 * @param dataSourceFormat the table's data source format, such as Delta; null for assets that
 *     are not tables
 */
public record ResolvedAsset(
    AssetType type,
    String subtype,
    String fullName,
    String catalogAssetId,
    AssetLocation location,
    DataSourceFormat dataSourceFormat) {

  public ResolvedAsset {
    Objects.requireNonNull(type, "type");
    if (fullName == null || fullName.isBlank()) {
      throw new IllegalArgumentException("asset's full name in the catalog must not be blank");
    }
    if (type != AssetType.TABLE && dataSourceFormat != null) {
      throw new IllegalArgumentException(type + " asset must not have a data source format");
    }
  }

  public static Builder builder(AssetType type, String fullName) {
    return new Builder(type, fullName);
  }

  public static final class Builder {
    private final AssetType type;
    private final String fullName;
    private String subtype;
    private String catalogAssetId;
    private AssetLocation location;
    private DataSourceFormat dataSourceFormat;

    private Builder(AssetType type, String fullName) {
      this.type = type;
      this.fullName = fullName;
    }

    public Builder subtype(String value) {
      this.subtype = value;
      return this;
    }

    public Builder catalogAssetId(String value) {
      this.catalogAssetId = value;
      return this;
    }

    public Builder location(AssetLocation value) {
      this.location = value;
      return this;
    }

    public Builder dataSourceFormat(DataSourceFormat value) {
      this.dataSourceFormat = value;
      return this;
    }

    public ResolvedAsset build() {
      return new ResolvedAsset(type, subtype, fullName, catalogAssetId, location, dataSourceFormat);
    }
  }
}
