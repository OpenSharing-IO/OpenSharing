package io.opensharing.asset.table;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.http.ApiException;
import java.util.Map;

/** Directory-scoped cloud credentials for {@code accessModes} including {@code dir}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TemporaryCredentials(
    String location,
    AwsCredentials awsTempCredentials,
    AzureUserDelegationSas azureUserDelegationSas,
    GcpOauthToken gcpOauthToken,
    R2Credentials r2Credentials,
    Long expirationTime) {

  static TemporaryCredentials from(StorageCredentials minted) {
    Long expiration =
        minted.expiration() == null ? null : minted.expiration().toEpochMilli();
    Map<String, String> values = minted.credentials();
    return switch (minted.provider()) {
      case AWS ->
          new TemporaryCredentials(
              minted.prefix(), aws(values), null, null, null, expiration);
      case AZURE ->
          new TemporaryCredentials(
              minted.prefix(),
              null,
              new AzureUserDelegationSas(minted.require(StorageCredentials.SAS_TOKEN)),
              null,
              null,
              expiration);
      case GCP ->
          new TemporaryCredentials(
              minted.prefix(),
              null,
              null,
              new GcpOauthToken(minted.require(StorageCredentials.OAUTH_TOKEN)),
              null,
              expiration);
      case R2 ->
          new TemporaryCredentials(
              minted.prefix(), null, null, null, r2(values), expiration);
    };
  }

  private static AwsCredentials aws(Map<String, String> values) {
    String access = values.get(StorageCredentials.ACCESS_KEY_ID);
    String secret = values.get(StorageCredentials.SECRET_ACCESS_KEY);
    if (access == null || access.isBlank() || secret == null || secret.isBlank()) {
      throw ApiException.invalidParameter("catalog AWS credentials are missing access keys");
    }
    String session = values.get(StorageCredentials.SESSION_TOKEN);
    return new AwsCredentials(access, secret, blankToNull(session));
  }

  private static R2Credentials r2(Map<String, String> values) {
    String access = values.get(StorageCredentials.ACCESS_KEY_ID);
    String secret = values.get(StorageCredentials.SECRET_ACCESS_KEY);
    if (access == null || access.isBlank() || secret == null || secret.isBlank()) {
      throw ApiException.invalidParameter("catalog R2 credentials are missing access keys");
    }
    return new R2Credentials(
        access, secret, blankToNull(values.get(StorageCredentials.SESSION_TOKEN)));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  public record AwsCredentials(String accessKeyId, String secretAccessKey, String sessionToken) {}

  public record AzureUserDelegationSas(String sasToken) {}

  public record GcpOauthToken(String oauthToken) {}

  public record R2Credentials(String accessKeyId, String secretAccessKey, String sessionToken) {}
}
