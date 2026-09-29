package io.opensharing.catalog;

import io.opensharing.exception.CatalogException;
import java.time.Instant;
import java.util.Map;

/** Time-bounded credentials for one storage prefix. */
public record StorageCredentials(
    String prefix, CloudProvider provider, Map<String, String> credentials, Instant expiration) {

  // AWS / R2
  public static final String ACCESS_KEY_ID = "accessKeyId";
  public static final String SECRET_ACCESS_KEY = "secretAccessKey";
  public static final String SESSION_TOKEN = "sessionToken";
  public static final String REGION = "region";

  // Azure
  public static final String SAS_TOKEN = "sasToken";
  /** Local integration tests only; production catalogs vend {@link #SAS_TOKEN}. */
  public static final String AZURE_ACCOUNT_KEY = "azureAccountKey";

  // GCP
  public static final String OAUTH_TOKEN = "oauthToken";
  /** Local integration tests only; production catalogs vend {@link #OAUTH_TOKEN}. */
  public static final String GOOGLE_SERVICE_ACCOUNT_KEY_FILE = "googleServiceAccountKeyFile";

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
