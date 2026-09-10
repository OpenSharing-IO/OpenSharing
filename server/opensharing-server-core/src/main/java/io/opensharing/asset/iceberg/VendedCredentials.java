package io.opensharing.asset.iceberg;

import io.opensharing.catalog.StorageCredentialKeys;
import io.opensharing.catalog.StorageCredentials;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * States the catalog's credentials in the spelling an Iceberg client reads them in.
 *
 * <p>The values are the same ones {@code temporary-table-credentials} hands out; only the keys
 * differ, and they are the keys Iceberg's own file IO looks under, so an engine configured against
 * this catalog reads the table's files with no further setup. Each carries when it stops working,
 * which is what lets a client come back for a fresh one before a long scan fails halfway.
 */
final class VendedCredentials {

  private static final String S3_ACCESS_KEY_ID = "s3.access-key-id";
  private static final String S3_SECRET_ACCESS_KEY = "s3.secret-access-key";
  private static final String S3_SESSION_TOKEN = "s3.session-token";
  private static final String S3_SESSION_TOKEN_EXPIRES_AT = "s3.session-token-expires-at-ms";
  private static final String CLIENT_REGION = "client.region";
  private static final String ADLS_SAS_TOKEN = "adls.sas-token";
  private static final String ADLS_SAS_TOKEN_EXPIRES_AT = "adls.sas-token-expires-at-ms";
  private static final String GCS_OAUTH2_TOKEN = "gcs.oauth2.token";
  private static final String GCS_OAUTH2_TOKEN_EXPIRES_AT = "gcs.oauth2.token-expires-at";

  private VendedCredentials() {}

  /**
   * @param credentials a grant the catalog minted, scoped to {@link StorageCredentials#prefix()}
   */
  static Map<String, String> propertiesOf(StorageCredentials credentials) {
    Map<String, String> properties = new LinkedHashMap<>();
    Long expiration =
        credentials.expiration() == null ? null : credentials.expiration().toEpochMilli();
    switch (credentials.provider()) {
      case AWS, R2 -> {
        properties.put(
            S3_ACCESS_KEY_ID, credentials.require(StorageCredentialKeys.ACCESS_KEY_ID));
        properties.put(
            S3_SECRET_ACCESS_KEY, credentials.require(StorageCredentialKeys.SECRET_ACCESS_KEY));
        properties.put(S3_SESSION_TOKEN, credentials.require(StorageCredentialKeys.SESSION_TOKEN));
        put(properties, S3_SESSION_TOKEN_EXPIRES_AT, expiration);
        String region = credentials.credentials().get(StorageCredentialKeys.REGION);
        if (region != null && !region.isBlank()) {
          properties.put(CLIENT_REGION, region);
        }
      }
      // Iceberg's own file IO wants the token keyed by the account it is for, since one client can
      // read from several — and it has changed its mind about what names an account: older ones
      // key by the whole host, newer ones by the account name alone. All three are stated, the bare
      // key included, because which one a recipient's engine looks under is not this server's to
      // know, and a token under a key nobody reads costs the recipient nothing.
      case AZURE -> {
        String sas = credentials.require(StorageCredentialKeys.SAS_TOKEN);
        properties.put(ADLS_SAS_TOKEN, sas);
        put(properties, ADLS_SAS_TOKEN_EXPIRES_AT, expiration);
        for (String account : accountsOf(credentials.prefix())) {
          properties.put(ADLS_SAS_TOKEN + "." + account, sas);
          put(properties, ADLS_SAS_TOKEN_EXPIRES_AT + "." + account, expiration);
        }
      }
      case GCP -> {
        properties.put(GCS_OAUTH2_TOKEN, credentials.require(StorageCredentialKeys.OAUTH_TOKEN));
        put(properties, GCS_OAUTH2_TOKEN_EXPIRES_AT, expiration);
      }
    }
    return properties;
  }

  /**
   * The ways to name the account a prefix such as {@code abfss://container@account.dfs.core}
   * {@code .windows.net/} is on: the host itself, and the account name in front of it.
   */
  private static List<String> accountsOf(String prefix) {
    if (prefix == null || !prefix.contains("://")) {
      return List.of();
    }
    String host;
    try {
      host = URI.create(prefix).getHost();
    } catch (IllegalArgumentException e) {
      return List.of();
    }
    if (host == null || host.isBlank()) {
      return List.of();
    }
    int suffix = host.indexOf('.');
    return suffix <= 0 ? List.of(host) : List.of(host, host.substring(0, suffix));
  }

  private static void put(Map<String, String> properties, String key, Long value) {
    if (value != null) {
      properties.put(key, Long.toString(value));
    }
  }
}
