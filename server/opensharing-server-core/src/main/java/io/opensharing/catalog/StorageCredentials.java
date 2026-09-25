package io.opensharing.catalog;

import io.opensharing.exception.CatalogException;
import java.time.Instant;
import java.util.Map;

/** Time-bounded credentials for one storage prefix. */
public record StorageCredentials(
    String prefix, CloudProvider provider, Map<String, String> credentials, Instant expiration) {

  public static final String ACCESS_KEY_ID = "accessKeyId";
  public static final String SECRET_ACCESS_KEY = "secretAccessKey";
  public static final String SESSION_TOKEN = "sessionToken";
  public static final String AZURE_ACCOUNT_KEY = "azureAccountKey";
  public static final String SAS_TOKEN = "sasToken";
  public static final String GOOGLE_SERVICE_ACCOUNT_KEY_FILE = "googleServiceAccountKeyFile";
  public static final String OAUTH_TOKEN = "oauthToken";
  public static final String REGION = "region";

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
