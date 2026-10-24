package io.opensharing.asset.table;

import io.opensharing.catalog.StorageCredentials;
import io.opensharing.exception.CatalogException;

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
 *  "expirationTime": <epoch milliseconds>}
 * }</pre>
 */
public record TemporaryCredentials(
    String location,
    AwsTempCredentials awsTempCredentials,
    AzureUserDelegationSas azureUserDelegationSas,
    GcpOauthToken gcpOauthToken,
    Long expirationTime) {

  static TemporaryCredentials from(StorageCredentials minted) {
    String location = minted.prefix();
    Long expiration = minted.expiration() == null ? null : minted.expiration().toEpochMilli();
    return switch (minted.provider()) {
      case AWS ->
          new TemporaryCredentials(
              location,
              new AwsTempCredentials(
                  minted.require(StorageCredentials.ACCESS_KEY_ID),
                  minted.require(StorageCredentials.SECRET_ACCESS_KEY),
                  minted.require(StorageCredentials.SESSION_TOKEN)),
              null,
              null,
              expiration);
      case AZURE ->
          new TemporaryCredentials(
              location,
              null,
              new AzureUserDelegationSas(minted.require(StorageCredentials.SAS_TOKEN)),
              null,
              expiration);
      case GCP ->
          new TemporaryCredentials(
              location,
              null,
              null,
              new GcpOauthToken(minted.require(StorageCredentials.OAUTH_TOKEN)),
              expiration);
      case R2 ->
          throw new CatalogException("the sharing protocol has no temporary credentials for R2");
    };
  }

  public record AwsTempCredentials(
      String accessKeyId, String secretAccessKey, String sessionToken) {}

  public record AzureUserDelegationSas(String sasToken) {}

  public record GcpOauthToken(String oauthToken) {}
}
