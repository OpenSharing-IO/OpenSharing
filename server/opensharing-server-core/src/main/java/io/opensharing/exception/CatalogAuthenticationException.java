package io.opensharing.exception;

/** The catalog rejected the connector's credentials. */
public class CatalogAuthenticationException extends CatalogException {

  public CatalogAuthenticationException(String message) {
    super(message);
  }

  public CatalogAuthenticationException(String message, Throwable cause) {
    super(message, cause);
  }
}
