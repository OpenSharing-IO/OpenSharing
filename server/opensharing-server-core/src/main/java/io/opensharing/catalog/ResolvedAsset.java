package io.opensharing.catalog;

/**
 * What the catalog knows about an asset, as returned by {@link CatalogConnector#resolveAsset} and
 * {@link CatalogConnector#listChildren}: its identity, where its data lives, and for tables, the
 * format OpenSharing needs to serve them.
 *
 * @param type the asset's type
 * @param fullName the asset's full name in the catalog, such as {@code main.sales.orders}
 * @param catalogAssetId the catalog's own stable id for the asset; optional
 * @param location where the asset's files live in storage
 * @param format the table format, for tables
 */
public record ResolvedAsset(
    AssetType type,
    String fullName,
    String catalogAssetId,
    AssetLocation location,
    TableFormat format) {

  public static Builder builder(AssetType type, String fullName) {
    return new Builder(type, fullName);
  }

  public static final class Builder {
    private final AssetType type;
    private final String fullName;
    private String catalogAssetId;
    private AssetLocation location;
    private TableFormat format;

    private Builder(AssetType type, String fullName) {
      this.type = type;
      this.fullName = fullName;
    }

    public Builder catalogAssetId(String value) {
      this.catalogAssetId = value;
      return this;
    }

    public Builder location(AssetLocation value) {
      this.location = value;
      return this;
    }

    public Builder format(TableFormat value) {
      this.format = value;
      return this;
    }

    public ResolvedAsset build() {
      return new ResolvedAsset(type, fullName, catalogAssetId, location, format);
    }
  }
}
