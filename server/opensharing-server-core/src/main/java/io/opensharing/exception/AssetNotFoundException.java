package io.opensharing.exception;

import io.opensharing.catalog.Asset;

/** No catalog asset has this full name. */
public class AssetNotFoundException extends CatalogException {

  public AssetNotFoundException(Asset asset) {
    super(asset.type() + " '" + asset.fullName() + "' does not exist in the catalog");
  }
}
