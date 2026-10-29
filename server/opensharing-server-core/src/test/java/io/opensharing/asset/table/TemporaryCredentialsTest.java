package io.opensharing.asset.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.exception.CatalogException;
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
  }

  @Test
  void rejectsAwsKeysWithoutASessionToken() {
    StorageCredentials longLived =
        minted(
            "s3://bucket/table/",
            CloudProvider.AWS,
            Map.of(
                StorageCredentials.ACCESS_KEY_ID, "AKIATEST",
                StorageCredentials.SECRET_ACCESS_KEY, "secret"));
    assertThrows(CatalogException.class, () -> TemporaryCredentials.from(longLived));
  }

  @Test
  void mapsR2SessionCredentials() {
    TemporaryCredentials credentials =
        TemporaryCredentials.from(
            minted(
                "s3://r2/table",
                CloudProvider.R2,
                Map.of(
                    StorageCredentials.ACCESS_KEY_ID, "r2key",
                    StorageCredentials.SECRET_ACCESS_KEY, "r2secret",
                    StorageCredentials.SESSION_TOKEN, "session")));
    assertEquals("s3://r2/table", credentials.location());
    assertEquals("r2key", credentials.r2Credentials().accessKeyId());
    assertEquals("r2secret", credentials.r2Credentials().secretAccessKey());
    assertEquals("session", credentials.r2Credentials().sessionToken());
    assertEquals(EXPIRATION.toEpochMilli(), credentials.expirationTime());
    assertNull(credentials.awsTempCredentials());
    assertNull(credentials.azureUserDelegationSas());
    assertNull(credentials.gcpOauthToken());
  }

  @Test
  void rejectsCredentialsWithoutAnExpiration() {
    StorageCredentials unbounded =
        new StorageCredentials(
            "gs://bucket/table",
            CloudProvider.GCP,
            Map.of(StorageCredentials.OAUTH_TOKEN, "ya29.token"),
            null);
    assertThrows(CatalogException.class, () -> TemporaryCredentials.from(unbounded));
  }

  @Test
  void toStringRedactsSecrets() {
    TemporaryCredentials.AwsTempCredentials aws =
        new TemporaryCredentials.AwsTempCredentials("ASIATEST", "secret", "session");
    assertEquals("AwsTempCredentials[redacted]", aws.toString());
    TemporaryCredentials credentials =
        new TemporaryCredentials("s3://bucket/table/", aws, null, null, null, 0);
    assertFalse(credentials.toString().contains("secret"));
    assertEquals(
        "AzureUserDelegationSas[redacted]",
        new TemporaryCredentials.AzureUserDelegationSas("sv=sig").toString());
    assertEquals(
        "GcpOauthToken[redacted]", new TemporaryCredentials.GcpOauthToken("ya29.token").toString());
    assertEquals(
        "R2Credentials[redacted]",
        new TemporaryCredentials.R2Credentials("r2key", "r2secret", "session").toString());
  }

  private static StorageCredentials minted(
      String location, CloudProvider provider, Map<String, String> values) {
    return new StorageCredentials(location, provider, values, EXPIRATION);
  }
}
