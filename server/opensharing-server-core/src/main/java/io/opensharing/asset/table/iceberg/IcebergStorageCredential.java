package io.opensharing.asset.table.iceberg;

import io.opensharing.asset.table.StorageLocations;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.exception.CatalogException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Catalog credentials for one storage prefix as an Iceberg REST {@code storage-credentials} entry,
 * under the property names Iceberg's file IO reads them by, so an Iceberg client reads the table's
 * files with no other setup:
 *
 * <pre>{@code
 * {"prefix": "<storage location>",
 *  "config": {"s3.access-key-id": "<key id>", "s3.secret-access-key": "<secret>", ...}}
 * }</pre>
 *
 * <p>Each set carries when it expires, so a client can refresh it before a long scan fails.
 */
public record IcebergStorageCredential(String prefix, Map<String, String> config) {

  static final String S3_ACCESS_KEY_ID = "s3.access-key-id";
  static final String S3_SECRET_ACCESS_KEY = "s3.secret-access-key";
  static final String S3_SESSION_TOKEN = "s3.session-token";
  static final String S3_SESSION_TOKEN_EXPIRES_AT_MS = "s3.session-token-expires-at-ms";
  static final String S3_ENDPOINT = "s3.endpoint";
  static final String S3_PATH_STYLE_ACCESS = "s3.path-style-access";
  static final String CLIENT_REGION = "client.region";
  static final String ADLS_SAS_TOKEN = "adls.sas-token.";
  static final String ADLS_SAS_TOKEN_EXPIRES_AT_MS = "adls.sas-token-expires-at-ms.";
  static final String GCS_OAUTH2_TOKEN = "gcs.oauth2.token";
  static final String GCS_OAUTH2_TOKEN_EXPIRES_AT = "gcs.oauth2.token-expires-at";

  public IcebergStorageCredential {
    config = config == null ? Map.of() : Map.copyOf(config);
  }

  /** {@code minted}'s credentials under Iceberg's names and file IO path scheme. */
  public static IcebergStorageCredential from(StorageCredentials minted) {
    if (minted.expiration() == null) {
      throw new CatalogException("the catalog returned credentials without an expiration");
    }
    if (minted.provider() == null) {
      throw new CatalogException("the catalog returned credentials without a cloud provider");
    }
    if (minted.prefix() == null || minted.prefix().isBlank()) {
      throw new CatalogException("the catalog returned credentials without a storage prefix");
    }
    String expiresAt = Long.toString(minted.expiration().toEpochMilli());
    Map<String, String> config = new LinkedHashMap<>();
    String prefix = switch (minted.provider()) {
      case AWS -> {
        s3(config, minted, expiresAt);
        yield minted.prefix();
      }
      case R2 -> {
        s3(config, minted, expiresAt);
        StorageLocations.R2Object object;
        try {
          object = StorageLocations.r2Object(minted.prefix());
        } catch (IllegalStateException invalid) {
          throw new CatalogException(
              "the catalog returned credentials for an invalid R2 prefix", invalid);
        }
        config.put(S3_ENDPOINT, "https://" + object.host());
        config.put(S3_PATH_STYLE_ACCESS, "true");
        config.put(CLIENT_REGION, "auto");
        yield "s3://" + object.bucket() + "/" + object.key();
      }
      case AZURE -> {
        String sasToken = minted.require(StorageCredentials.SAS_TOKEN);
        // Iceberg releases differ on whether the token is keyed by the account's host or by its
        // name, so both are set.
        for (String account : azureAccounts(minted.prefix())) {
          config.put(ADLS_SAS_TOKEN + account, sasToken);
          config.put(ADLS_SAS_TOKEN_EXPIRES_AT_MS + account, expiresAt);
        }
        yield minted.prefix();
      }
      case GCP -> {
        config.put(GCS_OAUTH2_TOKEN, minted.require(StorageCredentials.OAUTH_TOKEN));
        config.put(GCS_OAUTH2_TOKEN_EXPIRES_AT, expiresAt);
        yield minted.prefix();
      }
    };
    return new IcebergStorageCredential(prefix, config);
  }

  /** Legacy loadTable config, including the refresh endpoint when the credential can expire. */
  public Map<String, String> configWithRefreshEndpoint(String endpoint) {
    Map<String, String> result = new LinkedHashMap<>(config);
    if (config.containsKey(S3_SESSION_TOKEN)
        && config.containsKey(S3_SESSION_TOKEN_EXPIRES_AT_MS)) {
      result.put("client.refresh-credentials-endpoint", endpoint);
    } else if (config.containsKey(GCS_OAUTH2_TOKEN)
        && config.containsKey(GCS_OAUTH2_TOKEN_EXPIRES_AT)) {
      result.put("gcs.oauth2.refresh-credentials-endpoint", endpoint);
    } else if (config.keySet().stream()
        .anyMatch(key -> key.startsWith(ADLS_SAS_TOKEN_EXPIRES_AT_MS))) {
      result.put("adls.refresh-credentials-endpoint", endpoint);
    }
    return Map.copyOf(result);
  }

  /**
   * Legacy loadTable config: the first credential's properties with its own refresh endpoint only.
   * A client that reads only config holds just that credential, so advertising another
   * credential's refresher would switch its file IO to refreshing credentials it doesn't have.
   */
  public static Map<String, String> legacyConfig(
      List<IcebergStorageCredential> credentials, String endpoint) {
    return credentials.get(0).configWithRefreshEndpoint(endpoint);
  }

  private static void s3(Map<String, String> config, StorageCredentials minted, String expiresAt) {
    config.put(S3_ACCESS_KEY_ID, minted.require(StorageCredentials.ACCESS_KEY_ID));
    config.put(S3_SECRET_ACCESS_KEY, minted.require(StorageCredentials.SECRET_ACCESS_KEY));
    String sessionToken = minted.credentials().get(StorageCredentials.SESSION_TOKEN);
    if (sessionToken != null && !sessionToken.isBlank()) {
      config.put(S3_SESSION_TOKEN, sessionToken);
      config.put(S3_SESSION_TOKEN_EXPIRES_AT_MS, expiresAt);
    }
    String region = minted.credentials().get(StorageCredentials.REGION);
    if (region != null && !region.isBlank()) {
      config.put(CLIENT_REGION, region);
    }
  }

  /**
   * The storage account host of an {@code abfss://container@account.dfs.core.windows.net/} prefix,
   * then the account name in front of it.
   */
  private static List<String> azureAccounts(String prefix) {
    int separator = prefix.indexOf("://");
    String rest = separator < 0 ? "" : prefix.substring(separator + 3);
    int slash = rest.indexOf('/');
    String authority = slash < 0 ? rest : rest.substring(0, slash);
    String host = authority.substring(authority.lastIndexOf('@') + 1);
    if (host.isBlank()) {
      throw new CatalogException(
          "the catalog returned Azure credentials for a prefix with no storage account host");
    }
    int dot = host.indexOf('.');
    return dot <= 0 ? List.of(host) : List.of(host, host.substring(0, dot));
  }

  /** Lists property names only; the values are live cloud secrets and must never be logged. */
  @Override
  public String toString() {
    return "IcebergStorageCredential[prefix="
        + prefix
        + ", config="
        + new TreeSet<>(config.keySet())
        + "]";
  }
}
