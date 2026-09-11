package io.opensharing.catalog.exception;

/** The catalog did not accept the bearer token, or refused {@code privilege}. */
public class CatalogAuthorizationException extends CatalogException {

  public CatalogAuthorizationException(String message) {
    super(message);
  }
}
