package io.opensharing.exception;

/**
 * The catalog authenticated the caller on {@code CatalogConnector#authorize}, but they lack the
 * requested privilege.
 */
public class CatalogAuthorizationException extends CatalogException {

  public CatalogAuthorizationException(String message) {
    super(message);
  }
}
