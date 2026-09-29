package io.opensharing.asset.table.signer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.config.OpenSharingProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class S3UrlSignerTest {

  private final S3UrlSigner signer = new S3UrlSigner(new OpenSharingProperties());

  @Test
  void signsS3aPathsWithOptionalSessionTokenAndCredentialRegion() {
    Instant expiration = Instant.now().plus(Duration.ofHours(1));
    StorageCredentials credentials =
        new StorageCredentials(
            "s3://delta-exchange-test/",
            CloudProvider.AWS,
            Map.of(
                StorageCredentials.ACCESS_KEY_ID, "AKIATEST",
                StorageCredentials.SECRET_ACCESS_KEY, "secret",
                StorageCredentials.SESSION_TOKEN, "session",
                StorageCredentials.REGION, "us-west-2"),
            expiration);

    SignedUrl signed =
        signer.sign(
            "s3a://delta-exchange-test/table2/date=2021-04-28/part.parquet",
            credentials,
            Duration.ofMinutes(15));

    assertTrue(
        signed
            .url()
            .startsWith(
                "https://delta-exchange-test.s3.us-west-2.amazonaws.com/table2/date%3D2021-04-28/part.parquet?"));
    assertTrue(signed.url().contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"));
    assertTrue(signed.url().contains("X-Amz-Security-Token=session"));
    assertTrue(signed.url().contains("X-Amz-Signature="));
    long remaining = Duration.between(Instant.now(), signed.expiration()).toSeconds();
    assertTrue(remaining >= 14 * 60 && remaining <= 15 * 60);
  }

  @Test
  void omitsSessionTokenAndUsesDefaultRegionWhenCatalogOmitsThem() {
    StorageCredentials credentials =
        new StorageCredentials(
            "s3://bucket/",
            CloudProvider.AWS,
            Map.of(
                StorageCredentials.ACCESS_KEY_ID, "AKIATEST",
                StorageCredentials.SECRET_ACCESS_KEY, "secret"),
            Instant.now().plus(Duration.ofHours(1)));

    SignedUrl signed = signer.sign("s3://bucket/key.parquet", credentials, Duration.ofMinutes(5));

    assertTrue(signed.url().startsWith("https://bucket.s3.us-east-1.amazonaws.com/key.parquet?"));
    assertFalse(signed.url().contains("X-Amz-Security-Token"));
  }
}
