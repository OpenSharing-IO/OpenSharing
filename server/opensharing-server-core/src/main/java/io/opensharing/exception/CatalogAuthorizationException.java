package io.opensharing.exception;

/**
 * The catalog refused the end user on {@code CatalogConnector#authorize}: their token is missing or
 * invalid, or they lack the requested privilege.
 */
public class CatalogAuthorizationException extends CatalogException {

  public CatalogAuthorizationException(String message) {
    super(message);
  }
}
