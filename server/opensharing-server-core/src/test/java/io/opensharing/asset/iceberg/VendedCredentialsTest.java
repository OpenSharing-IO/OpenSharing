package io.opensharing.asset.iceberg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.StorageCredentialKeys;
import io.opensharing.catalog.StorageCredentials;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The keys are the contract here: a client reads a vended credential out of the properties it was
 * given by name, and a name it does not know is a credential it does not have.
 */
class VendedCredentialsTest {

  private static final Instant EXPIRY = Instant.ofEpochMilli(1735689600000L);

  @Test
  void namesAnAwsSessionTheWayIcebergsS3FileIoReadsIt() {
    Map<String, String> properties =
        VendedCredentials.propertiesOf(
            new StorageCredentials(
                "s3://lake/sales/orders/",
                CloudProvider.AWS,
                Map.of(
                    StorageCredentialKeys.ACCESS_KEY_ID, "ASIAEXAMPLE",
                    StorageCredentialKeys.SECRET_ACCESS_KEY, "secret",
                    StorageCredentialKeys.SESSION_TOKEN, "token",
                    "region", "eu-west-1"),
                EXPIRY));

    assertEquals("ASIAEXAMPLE", properties.get("s3.access-key-id"));
    assertEquals("secret", properties.get("s3.secret-access-key"));
    assertEquals("token", properties.get("s3.session-token"));
    assertEquals("1735689600000", properties.get("s3.session-token-expires-at-ms"));
    assertEquals("eu-west-1", properties.get("client.region"));
  }

  @Test
  void leavesTheRegionOutWhenTheCatalogDoesNotNameOne() {
    Map<String, String> properties =
        VendedCredentials.propertiesOf(
            new StorageCredentials(
                "s3://lake/",
                CloudProvider.R2,
                Map.of(
                    StorageCredentialKeys.ACCESS_KEY_ID, "ASIAEXAMPLE",
                    StorageCredentialKeys.SECRET_ACCESS_KEY, "secret",
                    StorageCredentialKeys.SESSION_TOKEN, "token"),
                EXPIRY));

    assertFalse(properties.containsKey("client.region"));
  }

  /**
   * Iceberg's ADLS file IO looks the token up by the account it is for, and has changed its mind
   * about what names an account — the whole host, or the name in front of it — so a recipient on
   * either version has to find it. Other clients read the bare key.
   */
  @Test
  void keysAnAzureSasByEveryNameAClientMightLookItUpUnder() {
    Map<String, String> properties =
        VendedCredentials.propertiesOf(
            new StorageCredentials(
                "abfss://data@acme.dfs.core.windows.net/sales/orders/",
                CloudProvider.AZURE,
                Map.of(StorageCredentialKeys.SAS_TOKEN, "sv=2024-11-04&sig=abc"),
                EXPIRY));

    assertEquals("sv=2024-11-04&sig=abc", properties.get("adls.sas-token"));
    assertEquals(
        "sv=2024-11-04&sig=abc", properties.get("adls.sas-token.acme.dfs.core.windows.net"));
    assertEquals("sv=2024-11-04&sig=abc", properties.get("adls.sas-token.acme"));
    assertEquals(
        "1735689600000", properties.get("adls.sas-token-expires-at-ms.acme.dfs.core.windows.net"));
    assertEquals("1735689600000", properties.get("adls.sas-token-expires-at-ms.acme"));
  }

  @Test
  void namesAGcpTokenAndWhenItStopsWorking() {
    Map<String, String> properties =
        VendedCredentials.propertiesOf(
            new StorageCredentials(
                "gs://lake/sales/orders/",
                CloudProvider.GCP,
                Map.of(StorageCredentialKeys.OAUTH_TOKEN, "ya29.example"),
                null));

    assertEquals("ya29.example", properties.get("gcs.oauth2.token"));
    assertNull(
        properties.get("gcs.oauth2.token-expires-at"),
        "a catalog that does not say when its grant ends is not guessed for");
  }
}
