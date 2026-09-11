package io.opensharing.exception;

/** The catalog could not satisfy the request. */
public class CatalogException extends RuntimeException {

  public CatalogException(String message) {
    super(message);
  }

  public CatalogException(String message, Throwable cause) {
    super(message, cause);
  }
}
