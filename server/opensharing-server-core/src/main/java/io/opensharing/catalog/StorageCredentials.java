package io.opensharing.catalog;

import io.opensharing.catalog.exception.CatalogException;
import java.time.Instant;
import java.util.Map;

/** Time-bounded credentials for one storage prefix. */
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
