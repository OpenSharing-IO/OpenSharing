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

class GcsUrlSignerTest {

  @Test
  void appendsCatalogTokenToObjectUrl() {
    Instant expiration = Instant.now().plus(Duration.ofHours(1));

    SignedUrl signed =
        sign(
            "gs://bucket/path/file.parquet",
            tokenCredentials("ya29.catalog/token+1", expiration),
            Duration.ofMinutes(10));

    assertEquals(
        "https://storage.googleapis.com/bucket/path/file.parquet"
            + "?access_token=ya29.catalog%2Ftoken%2B1",
        signed.url());
    assertEquals(expiration, signed.expiration());
  }

  @Test
  void encodesSpacesInObjectPaths() {
    SignedUrl signed =
        sign(
            "gs://bucket/c2=foo bar/file.parquet",
            tokenCredentials("ya29.catalog", Instant.now().plus(Duration.ofHours(1))),
            Duration.ofMinutes(10));

    assertTrue(signed.url().contains("/bucket/c2%3Dfoo%20bar/file.parquet?"), signed.url());
  }

  @Test
  void rejectsCredentialsWithoutAnOauthToken() {
    StorageCredentials credentials =
        new StorageCredentials(
            "gs://bucket/", CloudProvider.GCP, Map.of(), Instant.now().plus(Duration.ofHours(1)));

    assertThrows(
        CatalogException.class,
        () -> sign("gs://bucket/path/file.parquet", credentials, Duration.ofMinutes(10)));
  }

  private static StorageCredentials tokenCredentials(String token, Instant expiration) {
    return new StorageCredentials(
        "gs://bucket/",
        CloudProvider.GCP,
        Map.of(StorageCredentials.OAUTH_TOKEN, token),
        expiration);
  }

  private static SignedUrl sign(String path, StorageCredentials credentials, Duration ttl) {
    try (SigningSession session = new GcsUrlSigner().open(credentials)) {
      return session.sign(path, ttl);
    }
  }
}
