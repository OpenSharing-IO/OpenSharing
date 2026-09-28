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
            new StorageCredentials(
                "s3://bucket/table/",
                CloudProvider.AWS,
                Map.of(
                    StorageCredentials.ACCESS_KEY_ID, "ASIATEST",
                    StorageCredentials.SECRET_ACCESS_KEY, "secret",
                    StorageCredentials.SESSION_TOKEN, "session"),
                EXPIRATION));
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
  void mapsAzureSasAndGcpOauthAndR2() {
    assertEquals(
        "sv=sig",
        TemporaryCredentials.from(
                new StorageCredentials(
                    "abfss://c@a.dfs.core.windows.net/t",
                    CloudProvider.AZURE,
                    Map.of(StorageCredentials.SAS_TOKEN, "sv=sig"),
                    EXPIRATION))
            .azureUserDelegationSas()
            .sasToken());
    assertEquals(
        "ya29.token",
        TemporaryCredentials.from(
                new StorageCredentials(
                    "gs://bucket/table",
                    CloudProvider.GCP,
                    Map.of(StorageCredentials.OAUTH_TOKEN, "ya29.token"),
                    EXPIRATION))
            .gcpOauthToken()
            .oauthToken());
    TemporaryCredentials r2 =
        TemporaryCredentials.from(
            new StorageCredentials(
                "s3://r2/table",
                CloudProvider.R2,
                Map.of(
                    StorageCredentials.ACCESS_KEY_ID, "r2key",
                    StorageCredentials.SECRET_ACCESS_KEY, "r2secret"),
                EXPIRATION));
    assertEquals("r2key", r2.r2Credentials().accessKeyId());
    assertNull(r2.r2Credentials().sessionToken());
  }
}
