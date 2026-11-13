package io.opensharing.asset.table.signer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.http.ApiException;
import io.opensharing.http.ErrorCodes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UrlSignersTest {

  private final UrlSigners signers =
      new UrlSigners(
          List.of(new S3UrlSigner(), new AzureSasUrlSigner()));

  @Test
  void signsBySchemeAndCapsTtlToCredentialExpiry() {
    Instant expiry = Instant.now().plus(Duration.ofMinutes(3));
    StorageCredentials credentials =
        new StorageCredentials(
            "s3://bucket/",
            CloudProvider.AWS,
            Map.of(
                StorageCredentials.ACCESS_KEY_ID, "AKIATEST",
                StorageCredentials.SECRET_ACCESS_KEY, "secret",
                StorageCredentials.REGION, "us-west-2"),
            expiry);

    SignedUrl signed = sign("s3://bucket/key.parquet", credentials, Duration.ofHours(1));

    assertTrue(signed.url().contains("X-Amz-Signature="), signed.url());
    assertTrue(signed.url().matches(".*X-Amz-Expires=17[0-9].*"), signed.url());
    long remaining = signed.expiration().getEpochSecond() - Instant.now().getEpochSecond();
    assertTrue(remaining >= 170 && remaining <= 180, String.valueOf(remaining));
  }

  @Test
  void rejectsUnknownSchemesAndExpiredCredentials() {
    StorageCredentials expired =
        new StorageCredentials(
            "s3://bucket/",
            CloudProvider.AWS,
            Map.of(
                StorageCredentials.ACCESS_KEY_ID, "AKIATEST",
                StorageCredentials.SECRET_ACCESS_KEY, "secret",
                StorageCredentials.REGION, "us-west-2"),
            Instant.now().minusSeconds(5));
    ApiException unknown =
        assertThrows(
            ApiException.class,
            () ->
                sign(
                    "file:///tmp/table/part.parquet",
                    expired,
                    Duration.ofMinutes(5)));
    assertEquals(501, unknown.getStatus());
    assertEquals(ErrorCodes.NOT_IMPLEMENTED, unknown.getErrorCode());

    ApiException alreadyExpired =
        assertThrows(
            ApiException.class,
            () -> sign("s3://bucket/key.parquet", expired, Duration.ofMinutes(5)));
    assertEquals(502, alreadyExpired.getStatus());
  }

  @Test
  void signsAzurePathsOnlyInAbfsForm() {
    StorageCredentials sas =
        new StorageCredentials(
            "abfss://container@account.dfs.core.windows.net/",
            CloudProvider.AZURE,
            Map.of(StorageCredentials.SAS_TOKEN, "sv=2020-12-06&sig=catalog"),
            Instant.now().plus(Duration.ofHours(1)));

    for (String scheme : List.of("abfs", "abfss")) {
      SignedUrl signed =
          sign(
              scheme + "://container@account.dfs.core.windows.net/table/part.parquet",
              sas,
              Duration.ofMinutes(5));
      assertTrue(
          signed.url().startsWith("https://account.blob.core.windows.net/container/table/"),
          signed.url());
    }
    for (String scheme : List.of("wasb", "wasbs")) {
      ApiException rejected =
          assertThrows(
              ApiException.class,
              () ->
                  sign(
                      scheme + "://container@account.blob.core.windows.net/table/part.parquet",
                      sas,
                      Duration.ofMinutes(5)));
      assertEquals(501, rejected.getStatus());
      assertEquals(
          "NOT_IMPLEMENTED: DS_UNSUPPORTED_STORAGE_SCHEME: Unsupported storage scheme: "
              + scheme
              + ".",
          rejected.getMessage());
    }
  }

  @Test
  void opensEachSignerOnceAndClosesItWithTheSession() {
    List<String> events = new ArrayList<>();
    UrlSigner recording =
        new UrlSigner() {
          @Override
          public Set<String> schemes() {
            return Set.of("s3");
          }

          @Override
          public SigningSession open(StorageCredentials credentials) {
            events.add("open");
            return new SigningSession() {
              @Override
              public SignedUrl sign(String path, Duration ttl) {
                events.add("sign " + path);
                return new SignedUrl(path, Instant.now().plus(ttl));
              }

              @Override
              public void close() {
                events.add("close");
              }
            };
          }
        };
    StorageCredentials credentials =
        new StorageCredentials(
            "s3://bucket/", CloudProvider.AWS, Map.of(), Instant.now().plus(Duration.ofHours(1)));

    try (SigningSession session = new UrlSigners(List.of(recording)).open(credentials)) {
      session.sign("s3://bucket/a.parquet", Duration.ofMinutes(5));
      session.sign("s3://bucket/b.parquet", Duration.ofMinutes(5));
    }

    assertEquals(
        List.of("open", "sign s3://bucket/a.parquet", "sign s3://bucket/b.parquet", "close"),
        events);
  }

  private SignedUrl sign(String path, StorageCredentials credentials, Duration ttl) {
    try (SigningSession session = signers.open(credentials)) {
      return session.sign(path, ttl);
    }
  }
}
