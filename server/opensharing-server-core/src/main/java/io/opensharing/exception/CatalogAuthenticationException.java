package io.opensharing.exception;

/**
 * The catalog rejected OpenSharing's own credentials, so the server cannot reach the catalog at
 * all. A server misconfiguration, not a problem with the end user's request.
 */
public class CatalogAuthenticationException extends CatalogException {

  public CatalogAuthenticationException(String message) {
    super(message);
  }

  public CatalogAuthenticationException(String message, Throwable cause) {
    super(message, cause);
  }
}
