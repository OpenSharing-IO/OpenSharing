package io.opensharing.asset.table.iceberg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.exception.CatalogException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IcebergStorageCredentialTest {

  private static final Instant EXPIRATION = Instant.parse("2026-01-01T00:00:00Z");
  private static final String EXPIRES_AT = Long.toString(EXPIRATION.toEpochMilli());

  @Test
  void rejectsCredentialsWithoutACloudProvider() {
    // A missing provider is a catalog error, rather than an unchecked null failure.
    CatalogException error =
        assertThrows(
            CatalogException.class,
            () ->
                IcebergStorageCredential.from(
                    new StorageCredentials("gs://bucket/table/", null, Map.of(), EXPIRATION)));
    // The error identifies the invalid catalog response.
    assertEquals("the catalog returned credentials without a cloud provider", error.getMessage());
  }

  @Test
  void rejectsCredentialsWithoutAPrefix() {
    // A missing or blank prefix is a catalog error, since clients pick credentials by prefix.
    for (String prefix : new String[] {null, " "}) {
      CatalogException error =
          assertThrows(
              CatalogException.class,
              () ->
                  IcebergStorageCredential.from(
                      new StorageCredentials(prefix, CloudProvider.AWS, Map.of(), EXPIRATION)));
      // The error identifies the invalid catalog response.
      assertEquals(
          "the catalog returned credentials without a storage prefix", error.getMessage());
    }
  }

  @Test
  void mapsAwsSessionCredentialsAndRegion() {
    IcebergStorageCredential credential =
        IcebergStorageCredential.from(
            new StorageCredentials(
                "s3://bucket/table/",
                CloudProvider.AWS,
                Map.of(
                    StorageCredentials.ACCESS_KEY_ID, "ASIATEST",
                    StorageCredentials.SECRET_ACCESS_KEY, "secret",
                    StorageCredentials.SESSION_TOKEN, "session",
                    StorageCredentials.REGION, "us-west-2"),
                EXPIRATION));

    assertEquals("s3://bucket/table/", credential.prefix());
    assertEquals(
        Map.of(
            "s3.access-key-id", "ASIATEST",
            "s3.secret-access-key", "secret",
            "s3.session-token", "session",
            "s3.session-token-expires-at-ms", EXPIRES_AT,
            "client.region", "us-west-2"),
        credential.config());
  }

  @Test
  void mapsR2CredentialsWithTheAccountsEndpoint() {
    IcebergStorageCredential credential =
        IcebergStorageCredential.from(
            new StorageCredentials(
                "r2://bucket@account.r2.cloudflarestorage.com/table/",
                CloudProvider.R2,
                Map.of(
                    StorageCredentials.ACCESS_KEY_ID, "key",
                    StorageCredentials.SECRET_ACCESS_KEY, "secret",
                    StorageCredentials.SESSION_TOKEN, "session"),
                EXPIRATION));

    // Iceberg's S3FileIO opens R2 objects through s3:// paths.
    assertEquals("s3://bucket/table/", credential.prefix());
    // The endpoint still identifies the R2 account.
    assertEquals(
        "https://account.r2.cloudflarestorage.com", credential.config().get("s3.endpoint"));
    assertEquals("true", credential.config().get("s3.path-style-access"));
    assertEquals("auto", credential.config().get("client.region"));
    assertEquals("key", credential.config().get("s3.access-key-id"));
  }

  @Test
  void keysAnAzureSasTokenByTheAccountsHostAndName() {
    IcebergStorageCredential credential =
        IcebergStorageCredential.from(
            new StorageCredentials(
                "abfss://container@account.dfs.core.windows.net/table/",
                CloudProvider.AZURE,
                Map.of(StorageCredentials.SAS_TOKEN, "sv=sig"),
                EXPIRATION));

    assertEquals(
        Map.of(
            "adls.sas-token.account.dfs.core.windows.net", "sv=sig",
            "adls.sas-token-expires-at-ms.account.dfs.core.windows.net", EXPIRES_AT,
            "adls.sas-token.account", "sv=sig",
            "adls.sas-token-expires-at-ms.account", EXPIRES_AT),
        credential.config());
  }

  @Test
  void mapsAGcsOauthToken() {
    IcebergStorageCredential credential =
        IcebergStorageCredential.from(
            new StorageCredentials(
                "gs://bucket/table/",
                CloudProvider.GCP,
                Map.of(StorageCredentials.OAUTH_TOKEN, "ya29.token"),
                EXPIRATION));

    assertEquals(
        Map.of("gcs.oauth2.token", "ya29.token", "gcs.oauth2.token-expires-at", EXPIRES_AT),
        credential.config());
  }

  @Test
  void requiresAnExpirationAndTheProvidersFields() {
    assertThrows(
        CatalogException.class,
        () ->
            IcebergStorageCredential.from(
                new StorageCredentials(
                    "gs://bucket/table/",
                    CloudProvider.GCP,
                    Map.of(StorageCredentials.OAUTH_TOKEN, "ya29.token"),
                    null)));
    assertThrows(
        CatalogException.class,
        () ->
            IcebergStorageCredential.from(
                new StorageCredentials(
                    "s3://bucket/table/",
                    CloudProvider.AWS,
                    Map.of(StorageCredentials.ACCESS_KEY_ID, "ASIATEST"),
                    EXPIRATION)));
  }

  @Test
  void neverPrintsTheSecrets() {
    String printed =
        IcebergStorageCredential.from(
                new StorageCredentials(
                    "gs://bucket/table/",
                    CloudProvider.GCP,
                    Map.of(StorageCredentials.OAUTH_TOKEN, "ya29.token"),
                    EXPIRATION))
            .toString();

    assertFalse(printed.contains("ya29.token"));
    assertTrue(printed.contains("gcs.oauth2.token"));
  }

  @Test
  void azurePathsNeedNotBeLegalUris() {
    // Hadoop paths may contain unescaped spaces or percent signs.
    for (String path : List.of("table with space/", "table%/")) {
      // Map the token using only the authority, not a URI parser for the path.
      IcebergStorageCredential credential =
          IcebergStorageCredential.from(
              new StorageCredentials(
                  "abfss://container@account.dfs.core.windows.net/" + path,
                  CloudProvider.AZURE,
                  Map.of(StorageCredentials.SAS_TOKEN, "sv=sig"),
                  EXPIRATION));

      // Both Iceberg account-key conventions retain the SAS token and expiration.
      assertEquals(
          Map.of(
              "adls.sas-token.account.dfs.core.windows.net", "sv=sig",
              "adls.sas-token-expires-at-ms.account.dfs.core.windows.net", EXPIRES_AT,
              "adls.sas-token.account", "sv=sig",
              "adls.sas-token-expires-at-ms.account", EXPIRES_AT),
          credential.config());
    }
  }

  @Test
  void rejectsMalformedCloudAuthoritiesAsCatalogErrors() {
    // An R2 prefix without an account is a catalog error, not a server failure.
    assertThrows(
        CatalogException.class,
        () ->
            IcebergStorageCredential.from(
                new StorageCredentials(
                    "r2://bucket/table/",
                    CloudProvider.R2,
                    Map.of(
                        StorageCredentials.ACCESS_KEY_ID, "key",
                        StorageCredentials.SECRET_ACCESS_KEY, "secret"),
                    EXPIRATION)));
    // An Azure authority must name its storage account host.
    assertThrows(
        CatalogException.class,
        () ->
            IcebergStorageCredential.from(
                new StorageCredentials(
                    "abfss://container@/table/",
                    CloudProvider.AZURE,
                    Map.of(StorageCredentials.SAS_TOKEN, "sv=sig"),
                    EXPIRATION)));
  }
}
