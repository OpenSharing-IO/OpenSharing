package io.opensharing.asset.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.StorageCredentialKeys;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.config.OpenSharingProperties;
import io.opensharing.http.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Choosing a signer for a path, and the ttl every signer is held to. */
class UrlSignersTest {

  private static final Duration TTL = Duration.ofHours(1);

  private static UrlSigners signers() {
    OpenSharingProperties properties = new OpenSharingProperties();
    return new UrlSigners(
        List.of(new S3UrlSigner(properties), new AzureSasUrlSigner(), new LocalFileUrlSigner()));
  }

  private static StorageCredentials expiring(Instant expiration) {
    return new StorageCredentials(
        "s3://lake/orders/",
        CloudProvider.AWS,
        Map.of(
            StorageCredentialKeys.ACCESS_KEY_ID, "ASIAEXAMPLE",
            StorageCredentialKeys.SECRET_ACCESS_KEY, "secret",
            StorageCredentialKeys.SESSION_TOKEN, "token"),
        expiration);
  }

  @Test
  void cutsTheTtlDownToWhatTheGrantHasLeft() {
    Instant expiry = Instant.now().plusSeconds(120);

    SignedUrl signed = signers().sign("s3://lake/orders/f.parquet", expiring(expiry), TTL);

    assertTrue(
        signed.expiration().isBefore(expiry.plusSeconds(1)),
        "the hour asked for outlives the two minutes the credentials have left");
    assertTrue(
        signed.expiration().isAfter(expiry.minusSeconds(5)),
        "and what the grant does have left is not thrown away");
  }

  /**
   * Nothing can be signed from a grant that has already run out, and saying so is better than
   * handing over a url that would only 403: the recipient cannot tell those apart from the outside.
   */
  @Test
  void refusesToSignFromCredentialsThatHaveAlreadyExpired() {
    StorageCredentials expired = expiring(Instant.now().minusSeconds(60));

    ApiException e =
        assertThrows(
            ApiException.class, () -> signers().sign("s3://lake/orders/f.parquet", expired, TTL));

    assertEquals(502, e.getStatus().value());
    assertTrue(e.getMessage().contains("already expired"), e.getMessage());
  }

  @Test
  void saysWhichStorageItCannotSignFor() {
    ApiException e =
        assertThrows(
            ApiException.class, () -> signers().sign("oss://bucket/orders/f.parquet", null, TTL));

    assertEquals(501, e.getStatus().value());
    assertTrue(e.getMessage().contains("'oss'"), e.getMessage());
  }
}
