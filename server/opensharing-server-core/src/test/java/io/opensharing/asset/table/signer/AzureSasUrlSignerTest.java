package io.opensharing.asset.table.signer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.StorageCredentials;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AzureSasUrlSignerTest {

  private final AzureSasUrlSigner signer = new AzureSasUrlSigner();

  @Test
  void appendsCatalogSasToHttpsBlobUrl() {
    StorageCredentials credentials =
        new StorageCredentials(
            "abfss://container@account.dfs.core.windows.net/",
            CloudProvider.AZURE,
            Map.of(StorageCredentials.SAS_TOKEN, "sv=2020-12-06&sig=catalog"),
            Instant.now().plus(Duration.ofHours(1)));

    SignedUrl signed =
        signer.sign(
            "abfss://container@account.dfs.core.windows.net/table/part.parquet",
            credentials,
            Duration.ofMinutes(15));

    assertTrue(
        signed
            .url()
            .startsWith(
                "https://account.blob.core.windows.net/container/table/part.parquet?sv=2020-12-06&sig=catalog"),
        signed.url());
  }

  @Test
  void mintsReadOnlyBlobSasFromAccountKey() {
    String accountKey = Base64.getEncoder().encodeToString(new byte[64]);
    StorageCredentials credentials =
        new StorageCredentials(
            "abfss://container@account.dfs.core.windows.net/",
            CloudProvider.AZURE,
            Map.of(StorageCredentials.AZURE_ACCOUNT_KEY, accountKey),
            Instant.now().plus(Duration.ofHours(1)));

    SignedUrl signed =
        signer.sign(
            "abfss://container@account.dfs.core.windows.net/table/part.parquet",
            credentials,
            Duration.ofMinutes(15));

    assertTrue(
        signed.url().startsWith("https://account.blob.core.windows.net/container/table/part.parquet?"),
        signed.url());
    assertTrue(signed.url().contains("sp=r"), signed.url());
    assertTrue(signed.url().contains("spr=https"), signed.url());
    assertTrue(signed.url().contains("sig="), signed.url());
  }

  @Test
  void encodesSpacesInBlobPaths() {
    String accountKey = Base64.getEncoder().encodeToString(new byte[64]);
    StorageCredentials credentials =
        new StorageCredentials(
            "abfss://container@account.dfs.core.windows.net/",
            CloudProvider.AZURE,
            Map.of(StorageCredentials.AZURE_ACCOUNT_KEY, accountKey),
            Instant.now().plus(Duration.ofHours(1)));

    SignedUrl signed =
        signer.sign(
            "abfss://container@account.dfs.core.windows.net/table/c2=foo bar/part.parquet",
            credentials,
            Duration.ofMinutes(15));

    assertTrue(signed.url().contains("/table/c2%3Dfoo%20bar/part.parquet?"), signed.url());
  }
}
