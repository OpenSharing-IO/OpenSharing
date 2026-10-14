package io.opensharing.asset.table;

import io.opensharing.catalog.StorageCredentials;

/**
 * Directory-scoped cloud credentials for tables whose {@code accessModes} include {@code dir}. Only
 * the field for the storage's cloud is set:
 *
 * <pre>{@code
 * {"location": "<storage location>",
 *  "awsTempCredentials": {"accessKeyId": "<key id>", "secretAccessKey": "<secret>",
 *                         "sessionToken": "<session token>"},
 *  "azureUserDelegationSas": {"sasToken": "<SAS token>"},
 *  "gcpOauthToken": {"oauthToken": "<OAuth token>"},
 *  "r2Credentials": {"accessKeyId": "<key id>", "secretAccessKey": "<secret>",
 *                    "sessionToken": "<session token>"},
 *  "expirationTime": <epoch milliseconds>}
 * }</pre>
 */
public record TemporaryCredentials(
    String location,
    KeyCredentials awsTempCredentials,
    AzureUserDelegationSas azureUserDelegationSas,
    GcpOauthToken gcpOauthToken,
    KeyCredentials r2Credentials,
    Long expirationTime) {

  static TemporaryCredentials from(StorageCredentials minted) {
    String location = minted.prefix();
    Long expiration = minted.expiration() == null ? null : minted.expiration().toEpochMilli();
    return switch (minted.provider()) {
      case AWS -> new TemporaryCredentials(location, keys(minted), null, null, null, expiration);
      case AZURE ->
          new TemporaryCredentials(
              location,
              null,
              new AzureUserDelegationSas(minted.require(StorageCredentials.SAS_TOKEN)),
              null,
              null,
              expiration);
      case GCP ->
          new TemporaryCredentials(
              location,
              null,
              null,
              new GcpOauthToken(minted.require(StorageCredentials.OAUTH_TOKEN)),
              null,
              expiration);
      case R2 -> new TemporaryCredentials(location, null, null, null, keys(minted), expiration);
    };
  }

  private static KeyCredentials keys(StorageCredentials minted) {
    String session = minted.credentials().get(StorageCredentials.SESSION_TOKEN);
    return new KeyCredentials(
        minted.require(StorageCredentials.ACCESS_KEY_ID),
        minted.require(StorageCredentials.SECRET_ACCESS_KEY),
        session == null || session.isBlank() ? null : session);
  }

  public record KeyCredentials(String accessKeyId, String secretAccessKey, String sessionToken) {}

  public record AzureUserDelegationSas(String sasToken) {}

  public record GcpOauthToken(String oauthToken) {}
}
