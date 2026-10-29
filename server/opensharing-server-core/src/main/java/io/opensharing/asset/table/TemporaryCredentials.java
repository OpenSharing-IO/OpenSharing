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
 *  "r2Credentials": {"accessKeyId": "<key id>", "secretAccessKey": "<secret>",
 *                    "sessionToken": "<session token>"},
 *  "expirationTime": <epoch milliseconds>}
 * }</pre>
 */
public record TemporaryCredentials(
    String location,
    AwsTempCredentials awsTempCredentials,
    AzureUserDelegationSas azureUserDelegationSas,
    GcpOauthToken gcpOauthToken,
    R2Credentials r2Credentials,
    long expirationTime) {

  static TemporaryCredentials from(StorageCredentials minted) {
    String location = minted.prefix();
    if (minted.expiration() == null) {
      throw new CatalogException("the catalog returned credentials without an expiration");
    }
    long expiration = minted.expiration().toEpochMilli();
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
              null,
              expiration);
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
      case R2 ->
          new TemporaryCredentials(
              location,
              null,
              null,
              null,
              new R2Credentials(
                  minted.require(StorageCredentials.ACCESS_KEY_ID),
                  minted.require(StorageCredentials.SECRET_ACCESS_KEY),
                  minted.require(StorageCredentials.SESSION_TOKEN)),
              expiration);
    };
  }

  /** The values are live cloud secrets and must never be logged. */
  public record AwsTempCredentials(
      String accessKeyId, String secretAccessKey, String sessionToken) {

    @Override
    public String toString() {
      return "AwsTempCredentials[redacted]";
    }
  }

  /** The token is a live cloud secret and must never be logged. */
  public record AzureUserDelegationSas(String sasToken) {

    @Override
    public String toString() {
      return "AzureUserDelegationSas[redacted]";
    }
  }

  /** The token is a live cloud secret and must never be logged. */
  public record GcpOauthToken(String oauthToken) {

    @Override
    public String toString() {
      return "GcpOauthToken[redacted]";
    }
  }

  /** The values are live cloud secrets and must never be logged. */
  public record R2Credentials(String accessKeyId, String secretAccessKey, String sessionToken) {

    @Override
    public String toString() {
      return "R2Credentials[redacted]";
    }
  }
}
