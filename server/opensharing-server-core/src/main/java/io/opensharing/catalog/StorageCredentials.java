package io.opensharing.catalog;

import io.opensharing.exception.CatalogException;
import java.time.Instant;
import java.util.Map;
import java.util.TreeSet;

/** Time-bounded credentials for one storage prefix. */
public record StorageCredentials(
    String prefix, CloudProvider provider, Map<String, String> credentials, Instant expiration) {

  // AWS / R2
  /** Access key id of the vended key pair. */
  public static final String ACCESS_KEY_ID = "accessKeyId";

  /** Secret paired with {@link #ACCESS_KEY_ID}. */
  public static final String SECRET_ACCESS_KEY = "secretAccessKey";

  /** Session token for temporary credentials; absent for long-lived keys. */
  public static final String SESSION_TOKEN = "sessionToken";

  /** Region of the bucket, used for S3 reads. */
  public static final String REGION = "region";

  // Azure
  /** Shared Access Signature token scoped to the storage prefix. */
  public static final String SAS_TOKEN = "sasToken";

  /** Local integration tests only; production catalogs vend {@link #SAS_TOKEN}. */
  public static final String AZURE_ACCOUNT_KEY = "azureAccountKey";

  // GCP
  /** OAuth 2.0 access token for Google Cloud Storage. */
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

  /** Lists credential keys only; the values are live cloud secrets and must never be logged. */
  @Override
  public String toString() {
    return "StorageCredentials[prefix="
        + prefix
        + ", provider="
        + provider
        + ", credentials="
        + new TreeSet<>(credentials.keySet())
        + ", expiration="
        + expiration
        + "]";
  }
}
