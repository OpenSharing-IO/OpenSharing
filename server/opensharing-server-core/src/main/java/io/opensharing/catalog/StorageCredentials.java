package io.opensharing.catalog;

import java.time.Instant;
import java.util.Map;

/**
 * Scoped, TTL-bounded storage credentials minted by the catalog.
 *
 * @param prefix the storage prefix the credentials are scoped to
 * @param credentials provider-specific values keyed by {@link StorageCredentialKeys}
 */
public record StorageCredentials(
    String prefix, CloudProvider provider, Map<String, String> credentials, Instant expiration) {

  public StorageCredentials {
    credentials = credentials == null ? Map.of() : Map.copyOf(credentials);
  }

  public String require(String key) {
    String value = credentials.get(key);
    if (value == null || value.isBlank()) {
      throw new CatalogException(
          "catalog returned " + provider + " credentials without required field '" + key + "'");
    }
    return value;
  }
}
