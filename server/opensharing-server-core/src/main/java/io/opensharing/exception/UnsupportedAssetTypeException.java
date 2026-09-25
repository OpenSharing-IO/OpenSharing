package io.opensharing.exception;

/** The connector does not support this asset type or operation. */
public class UnsupportedAssetTypeException extends CatalogException {

  public UnsupportedAssetTypeException(String message) {
    super(message);
  }
}
