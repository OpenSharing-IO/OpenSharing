package io.opensharing.catalog;

/** The catalog has no asset with the requested identifier and type. */
public class AssetNotFoundException extends CatalogException {

  public AssetNotFoundException(AssetLookup lookup) {
    super(lookup.type() + " '" + lookup.identifier() + "' does not exist in the catalog");
  }
}
