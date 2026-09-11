package io.opensharing.catalog;

/** Raised when the catalog cannot satisfy a request. */
public class CatalogException extends RuntimeException {

  public CatalogException(String message) {
    super(message);
  }

  public CatalogException(String message, Throwable cause) {
    super(message, cause);
  }
}
