package io.opensharing.catalog.unity;

import io.opensharing.catalog.CatalogException;

/**
 * A Unity Catalog response that was not a success, carried with its status so that whoever knows
 * what was being asked can say what it means.
 *
 * <p>The client cannot: a {@code 404} is a missing table to one request and a missing schema to the
 * next, and a {@code 403} needs the name of the asset and of the caller to be worth reading. So the
 * client reports the status and the catalog's own message, and the connector turns that into the
 * failure the sharing server understands. Being a {@link CatalogException} already means one that
 * escapes untranslated is still reported as the catalog refusing, rather than as a server bug.
 */
final class UnityApiException extends CatalogException {

  private final int status;

  UnityApiException(int status, String message) {
    super(message);
    this.status = status;
  }

  int status() {
    return status;
  }
}
