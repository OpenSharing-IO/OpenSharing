package io.opensharing.asset.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.StorageCredentials;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemporaryCredentialsTest {

  private static final Instant EXPIRATION = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void mapsAwsSessionCredentials() {
    TemporaryCredentials credentials =
        TemporaryCredentials.from(
            minted(
                "s3://bucket/table/",
                CloudProvider.AWS,
                Map.of(
                    StorageCredentials.ACCESS_KEY_ID, "ASIATEST",
                    StorageCredentials.SECRET_ACCESS_KEY, "secret",
                    StorageCredentials.SESSION_TOKEN, "session")));
    assertEquals("s3://bucket/table/", credentials.location());
    assertEquals("ASIATEST", credentials.awsTempCredentials().accessKeyId());
    assertEquals("secret", credentials.awsTempCredentials().secretAccessKey());
    assertEquals("session", credentials.awsTempCredentials().sessionToken());
    assertEquals(EXPIRATION.toEpochMilli(), credentials.expirationTime());
    assertNull(credentials.azureUserDelegationSas());
    assertNull(credentials.gcpOauthToken());
    assertNull(credentials.r2Credentials());
  }

  @Test
  void mapsAzureUserDelegationSas() {
    TemporaryCredentials credentials =
        TemporaryCredentials.from(
            minted(
                "abfss://c@a.dfs.core.windows.net/t",
                CloudProvider.AZURE,
                Map.of(StorageCredentials.SAS_TOKEN, "sv=sig")));
    assertEquals("abfss://c@a.dfs.core.windows.net/t", credentials.location());
    assertEquals("sv=sig", credentials.azureUserDelegationSas().sasToken());
    assertEquals(EXPIRATION.toEpochMilli(), credentials.expirationTime());
    assertNull(credentials.awsTempCredentials());
    assertNull(credentials.gcpOauthToken());
    assertNull(credentials.r2Credentials());
  }

  @Test
  void mapsGcpOauthToken() {
    TemporaryCredentials credentials =
        TemporaryCredentials.from(
            minted(
                "gs://bucket/table",
                CloudProvider.GCP,
                Map.of(StorageCredentials.OAUTH_TOKEN, "ya29.token")));
    assertEquals("gs://bucket/table", credentials.location());
    assertEquals("ya29.token", credentials.gcpOauthToken().oauthToken());
    assertEquals(EXPIRATION.toEpochMilli(), credentials.expirationTime());
    assertNull(credentials.awsTempCredentials());
    assertNull(credentials.azureUserDelegationSas());
    assertNull(credentials.r2Credentials());
  }

  @Test
  void mapsR2Credentials() {
    TemporaryCredentials credentials =
        TemporaryCredentials.from(
            minted(
                "s3://r2/table",
                CloudProvider.R2,
                Map.of(
                    StorageCredentials.ACCESS_KEY_ID, "r2key",
                    StorageCredentials.SECRET_ACCESS_KEY, "r2secret")));
    assertEquals("s3://r2/table", credentials.location());
    assertEquals("r2key", credentials.r2Credentials().accessKeyId());
    assertEquals("r2secret", credentials.r2Credentials().secretAccessKey());
    assertNull(credentials.r2Credentials().sessionToken());
    assertEquals(EXPIRATION.toEpochMilli(), credentials.expirationTime());
    assertNull(credentials.awsTempCredentials());
    assertNull(credentials.azureUserDelegationSas());
    assertNull(credentials.gcpOauthToken());
  }

  private static StorageCredentials minted(
      String location, CloudProvider provider, Map<String, String> values) {
    return new StorageCredentials(location, provider, values, EXPIRATION);
  }
}
