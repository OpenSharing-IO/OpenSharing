package io.opensharing.asset.table.signer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.exception.CatalogException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AzureSasUrlSignerTest {

  private final AzureSasUrlSigner signer = new AzureSasUrlSigner();

  @Test
  void appendsCatalogSasToHttpsBlobUrl() {
    Instant expiration = Instant.now().plus(Duration.ofHours(1));
    StorageCredentials credentials = sasCredentials(expiration);

    SignedUrl signed =
        sign(
            "abfss://container@account.dfs.core.windows.net/table/part.parquet",
            credentials,
            Duration.ofMinutes(15));

    assertEquals(
        "https://account.blob.core.windows.net/container/table/part.parquet"
            + "?sv=2020-12-06&sig=catalog",
        signed.url());
    assertEquals(expiration, signed.expiration());
  }

  @Test
  void encodesSpacesInBlobPaths() {
    SignedUrl signed =
        sign(
            "abfss://container@account.dfs.core.windows.net/table/c2=foo bar/part.parquet",
            sasCredentials(Instant.now().plus(Duration.ofHours(1))),
            Duration.ofMinutes(15));

    assertTrue(signed.url().contains("/table/c2%3Dfoo%20bar/part.parquet?"), signed.url());
  }

  @Test
  void rejectsCredentialsWithoutASasToken() {
    StorageCredentials credentials =
        new StorageCredentials(
            "abfss://container@account.dfs.core.windows.net/",
            CloudProvider.AZURE,
            Map.of(),
            Instant.now().plus(Duration.ofHours(1)));

    assertThrows(
        CatalogException.class,
        () ->
            sign(
                "abfss://container@account.dfs.core.windows.net/table/part.parquet",
                credentials,
                Duration.ofMinutes(15)));
  }

  private static StorageCredentials sasCredentials(Instant expiration) {
    return new StorageCredentials(
        "abfss://container@account.dfs.core.windows.net/",
        CloudProvider.AZURE,
        Map.of(StorageCredentials.SAS_TOKEN, "sv=2020-12-06&sig=catalog"),
        expiration);
  }

  private SignedUrl sign(String path, StorageCredentials credentials, Duration ttl) {
    try (SigningSession session = signer.open(credentials)) {
      return session.sign(path, ttl);
    }
  }
}
