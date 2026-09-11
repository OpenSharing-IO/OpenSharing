package io.opensharing.catalog;

/**
 * The catalog would not authenticate a provider-admin bearer token at all, or would not authorize
 * it for the privilege asked about — see {@link CatalogConnector#authorize}. The two are kept as
 * one exception because they are read the same way by whoever resolves this into a rejection: an
 * unrecognized token and a recognized one the catalog says no to both mean the request is refused,
 * not that this connector failed.
 */
public class CatalogAuthorizationException extends CatalogException {

  public CatalogAuthorizationException(String message) {
    super(message);
  }
}
