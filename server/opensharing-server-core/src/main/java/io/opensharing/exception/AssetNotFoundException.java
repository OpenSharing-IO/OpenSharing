package io.opensharing.exception;

import io.opensharing.catalog.AssetLookup;

/** No catalog asset matches the lookup. */
public class AssetNotFoundException extends CatalogException {

  public AssetNotFoundException(AssetLookup lookup) {
    super(lookup.type() + " '" + lookup.identifier() + "' does not exist in the catalog");
  }
}
