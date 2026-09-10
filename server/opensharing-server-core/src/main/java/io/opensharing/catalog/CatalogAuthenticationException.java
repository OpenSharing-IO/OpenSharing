package io.opensharing.catalog;

/** The sharing server's own service-principal credentials were rejected by the catalog. */
public class CatalogAuthenticationException extends CatalogException {

  public CatalogAuthenticationException(String message) {
    super(message);
  }

  public CatalogAuthenticationException(String message, Throwable cause) {
    super(message, cause);
  }
}
