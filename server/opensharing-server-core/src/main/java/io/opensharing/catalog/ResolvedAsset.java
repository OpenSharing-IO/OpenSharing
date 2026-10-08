package io.opensharing.catalog;

import java.util.Objects;

/**
 * What the catalog knows about an asset, as returned by {@link CatalogConnector#resolveAsset} and
 * {@link CatalogConnector#listChildren}: its identity, where its data lives, and the attributes
 * that only apply to its type.
 *
 * @param type the asset's type
 * @param fullName the asset's full name in the catalog, such as {@code main.sales.orders}
 * @param catalogAssetId the catalog's own stable id for the asset
 * @param location where the asset's files live in storage
 * @param additionalProperties attributes specific to {@code type}, such as {@link TableProperties}
 *     for a table; null when the catalog reports none
 */
public record ResolvedAsset(
    AssetType type,
    String fullName,
    String catalogAssetId,
    AssetLocation location,
    AdditionalProperties additionalProperties) {

  public ResolvedAsset {
    Objects.requireNonNull(type, "type");
    if (fullName == null || fullName.isBlank()) {
      throw new IllegalArgumentException("asset's full name in the catalog must not be blank");
    }
    if (additionalProperties != null && additionalProperties.type() != type) {
      throw new IllegalArgumentException(
          type + " asset must not have " + additionalProperties.type() + " properties");
    }
  }

  public static Builder builder(AssetType type, String fullName) {
    return new Builder(type, fullName);
  }

  public static final class Builder {
    private final AssetType type;
    private final String fullName;
    private String catalogAssetId;
    private AssetLocation location;
    private AdditionalProperties additionalProperties;

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

    public Builder additionalProperties(AdditionalProperties value) {
      this.additionalProperties = value;
      return this;
    }

    public ResolvedAsset build() {
      return new ResolvedAsset(type, fullName, catalogAssetId, location, additionalProperties);
    }
  }
}
