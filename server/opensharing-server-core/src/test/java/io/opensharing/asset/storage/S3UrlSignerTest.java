package io.opensharing.asset.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.StorageCredentialKeys;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.config.OpenSharingProperties;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The signature itself cannot be checked without S3, so these assert the parts a client and AWS both
 * depend on: where the url points, that every signed element is present, and that a temporary
 * credential's session token travels with it.
 */
class S3UrlSignerTest {

  private static final Duration TTL = Duration.ofMinutes(15);

  private static S3UrlSigner signer() {
    return new S3UrlSigner(new OpenSharingProperties());
  }

  private static StorageCredentials credentials(Map<String, String> extra) {
    Map<String, String> values = new LinkedHashMap<>();
    values.put(StorageCredentialKeys.ACCESS_KEY_ID, "ASIAEXAMPLE");
    values.put(StorageCredentialKeys.SECRET_ACCESS_KEY, "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLE");
    values.put(StorageCredentialKeys.SESSION_TOKEN, "FQoGZXIvYXdzEExample//token+with/chars=");
    values.putAll(extra);
    return new StorageCredentials(
        "s3://lake/sales/orders/", CloudProvider.AWS, values, Instant.now().plus(TTL));
  }

  @Test
  void pointsAtTheObjectAndCarriesEverythingAwsSigned() {
    SignedUrl signed =
        signer()
            .sign(
                "s3a://lake/sales/orders/country=NL/part-00000.snappy.parquet",
                credentials(Map.of()),
                TTL);
    URI url = URI.create(signed.url());

    assertEquals("https", url.getScheme());
    assertEquals("lake.s3.us-east-1.amazonaws.com", url.getHost());
    assertEquals("/sales/orders/country%3DNL/part-00000.snappy.parquet", url.getRawPath());
    String query = url.getRawQuery();
    assertTrue(query.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"), query);
    assertTrue(query.contains("X-Amz-Expires=900"), query);
    assertTrue(query.contains("X-Amz-SignedHeaders=host"), query);
    assertTrue(query.contains("X-Amz-Security-Token=FQoGZXIvYXdzEExample%2F%2Ftoken%2Bwith%2Fchars%3D"), query);
    assertTrue(query.contains("&X-Amz-Signature="), query);
  }

  @Test
  void signsInTheRegionTheCatalogNamed() {
    SignedUrl signed =
        signer().sign("s3://lake/t/f.parquet", credentials(Map.of("region", "eu-west-1")), TTL);

    assertTrue(signed.url().startsWith("https://lake.s3.eu-west-1.amazonaws.com/"), signed.url());
    assertTrue(signed.url().contains("%2Feu-west-1%2Fs3%2Faws4_request"), signed.url());
  }

  @Test
  void signsDifferentlyForDifferentObjects() {
    S3UrlSigner signer = signer();
    StorageCredentials credentials = credentials(Map.of());

    String one = signature(signer.sign("s3://lake/t/a.parquet", credentials, TTL));
    String other = signature(signer.sign("s3://lake/t/b.parquet", credentials, TTL));

    assertTrue(!one.equals(other), "the object path is part of what is signed");
  }

  private static String signature(SignedUrl signed) {
    return signed.url().substring(signed.url().indexOf("X-Amz-Signature="));
  }
}
