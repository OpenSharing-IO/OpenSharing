package io.opensharing.catalog;

/**
 * What the catalog knows about an asset that only applies to its type. Each {@link AssetType} that
 * needs any has its own implementation.
 */
public sealed interface AdditionalProperties permits TableProperties {

  /** The asset type these properties describe. */
  AssetType type();
}
