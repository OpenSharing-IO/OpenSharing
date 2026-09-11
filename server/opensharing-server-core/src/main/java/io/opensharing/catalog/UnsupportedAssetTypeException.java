package io.opensharing.catalog;

/** The connector cannot handle the requested asset type or operation. */
public class UnsupportedAssetTypeException extends CatalogException {

  public UnsupportedAssetTypeException(String message) {
    super(message);
  }
}
