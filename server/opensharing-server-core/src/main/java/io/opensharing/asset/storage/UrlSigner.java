package io.opensharing.asset.storage;

import io.opensharing.catalog.StorageCredentials;
import java.time.Duration;
import java.util.Set;

/**
 * Turns a storage path into a url readable with no credentials of its own. This is the other half of
 * url access mode: the catalog grants the server temporary access, and a signer hands out a
 * narrower, shorter-lived slice of it — one file, read-only, for minutes.
 *
 * <p>Signing is per storage scheme rather than per cloud, because the scheme is what decides the
 * url's shape.
 */
public interface UrlSigner {

  /** The url schemes this signer handles, lower-case and without {@code ://}. */
  Set<String> schemes();

  /**
   * @param path an absolute storage path, as the catalog or a table's own metadata states it
   * @param credentials the grant to derive the url from, which bounds what it can reach
   * @param ttl how long the url should last, never beyond the credentials' own expiry
   */
  SignedUrl sign(String path, StorageCredentials credentials, Duration ttl);
}
