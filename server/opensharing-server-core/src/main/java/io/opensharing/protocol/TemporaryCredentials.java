package io.opensharing.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response of the {@code temporary-table-credentials} endpoint. Exactly one of the cloud-specific
 * fields is populated; {@code expirationTime} is epoch milliseconds.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TemporaryCredentials(
    AwsCredentials awsTempCredentials,
    AzureUserDelegationSas azureUserDelegationSas,
    GcpOauthToken gcpOauthToken,
    R2Credentials r2Credentials,
    long expirationTime) {

  public static TemporaryCredentials aws(AwsCredentials credentials, long expirationTime) {
    return new TemporaryCredentials(credentials, null, null, null, expirationTime);
  }

  public static TemporaryCredentials azure(AzureUserDelegationSas sas, long expirationTime) {
    return new TemporaryCredentials(null, sas, null, null, expirationTime);
  }

  public static TemporaryCredentials gcp(GcpOauthToken token, long expirationTime) {
    return new TemporaryCredentials(null, null, token, null, expirationTime);
  }

  public static TemporaryCredentials r2(R2Credentials credentials, long expirationTime) {
    return new TemporaryCredentials(null, null, null, credentials, expirationTime);
  }
}
