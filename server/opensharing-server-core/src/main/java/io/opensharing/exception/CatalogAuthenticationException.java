package io.opensharing.exception;

/**
 * The catalog could not authenticate the caller on {@code CatalogConnector#authorize}: their token
 * is missing or invalid.
 */
public class CatalogAuthenticationException extends CatalogException {

  public CatalogAuthenticationException(String message) {
    super(message);
  }

  public CatalogAuthenticationException(String message, Throwable cause) {
    super(message, cause);
  }
}
