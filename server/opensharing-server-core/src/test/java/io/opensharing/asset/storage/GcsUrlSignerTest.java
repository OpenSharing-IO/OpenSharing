package io.opensharing.asset.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.http.ApiException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Google's V4 signing, checked the only way it can be without a bucket: by verifying the signature
 * the signer produced against the public half of the key, over a string to sign this test builds
 * from Google's own specification of the algorithm rather than from the signer's code. A url that
 * verifies here is one whose canonical request, scope and query Google would reconstruct the same
 * way — the remaining risk is that Google wants a shape its documentation does not describe.
 */
class GcsUrlSignerTest {

  private static final String EMAIL = "sharing@example-project.iam.gserviceaccount.com";
  private static final String HOST = "storage.googleapis.com";

  private static KeyPair keyPair;
  private static GcsUrlSigner signer;

  @BeforeAll
  static void keys() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    keyPair = generator.generateKeyPair();
    signer = new GcsUrlSigner(GcsSigningKey.parse(serviceAccountKey(keyPair), "test-key.json"));
  }

  @Test
  void signsAUrlGoogleWouldAcceptTheSignatureOf() throws Exception {
    Instant before = Instant.now();

    SignedUrl signed =
        signer.sign("gs://lake/sales/orders/part-0.parquet", null, Duration.ofHours(1));

    URI url = URI.create(signed.url());
    assertEquals("https", url.getScheme());
    assertEquals(HOST, url.getHost());
    assertEquals("/lake/sales/orders/part-0.parquet", url.getRawPath());

    Map<String, String> query = queryOf(signed.url());
    assertEquals("GOOG4-RSA-SHA256", query.get("X-Goog-Algorithm"));
    assertEquals("host", query.get("X-Goog-SignedHeaders"));
    assertEquals("3600", query.get("X-Goog-Expires"));
    assertTrue(query.get("X-Goog-Credential").startsWith(EMAIL + "/"));
    assertTrue(
        query.get("X-Goog-Credential").endsWith("/auto/storage/goog4_request"),
        "the scope Google reads a signature under");

    assertTrue(
        verifies(signed.url(), keyPair.getPublic()),
        "the signature must verify over the string to sign Google's algorithm defines");
    assertFalse(
        before.plus(Duration.ofHours(1)).plusSeconds(5).isBefore(signed.expiration()),
        "a url lives exactly as long as it was asked to");
  }

  /** A key that signed a different request must not verify, or the check above proves nothing. */
  @Test
  void producesASignatureBoundToTheOneObject() throws Exception {
    String signed = signer.sign("gs://lake/a/one.parquet", null, Duration.ofHours(1)).url();
    String tampered = signed.replace("/lake/a/one.parquet", "/lake/a/two.parquet");

    assertTrue(verifies(signed, keyPair.getPublic()));
    assertFalse(verifies(tampered, keyPair.getPublic()));
  }

  /**
   * A Delta log states each file's path URI-encoded, so what arrives here is already escaped and
   * must come out escaped exactly once — encoding it again would sign a url for an object whose name
   * contains a literal {@code %20}, which is not the file the log meant.
   */
  @Test
  void carriesAnEncodedPathThroughUnchanged() throws Exception {
    String url =
        signer.sign("gs://lake/sales/a%20b/p%3D1/x.parquet", null, Duration.ofMinutes(5)).url();

    assertTrue(url.startsWith("https://" + HOST + "/lake/sales/a%20b/p%3D1/x.parquet?"), url);
    assertTrue(verifies(url, keyPair.getPublic()), "and the signature covers that same path");
    assertTrue(
        url.contains("X-Goog-Credential=" + EMAIL.replace("@", "%40") + "%2F"),
        "the slashes of the scope are encoded inside the value");
  }

  @Test
  void refusesAPathThatNamesNoObject() {
    assertThrows(
        ApiException.class, () -> signer.sign("gs://lake", null, Duration.ofMinutes(5)));
    assertThrows(
        ApiException.class, () -> signer.sign("gs://lake/", null, Duration.ofMinutes(5)));
  }

  /**
   * With no key there is nothing to sign with, and the signer says so by claiming no scheme, which
   * is what stops url access mode being offered for Google storage at all.
   */
  @Test
  void claimsNoSchemeWithoutAKey() {
    UrlSigners signers = new UrlSigners(List.of(new GcsUrlSigner((GcsSigningKey) null)));

    assertEquals(java.util.Set.of(), new GcsUrlSigner((GcsSigningKey) null).schemes());
    assertFalse(signers.canSign("gs://lake/sales/orders"));
    assertThrows(
        ApiException.class,
        () -> signers.sign("gs://lake/sales/orders/part-0.parquet", null, Duration.ofHours(1)));
  }

  @Test
  void claimsGoogleStorageOnceThereIsOne() {
    assertTrue(new UrlSigners(List.of(signer)).canSign("gs://lake/sales/orders"));
  }

  /**
   * Rebuilds what Google says it signs — canonical request, then string to sign — from the url alone,
   * and checks the signature against it.
   */
  private static boolean verifies(String url, PublicKey publicKey) throws Exception {
    String withoutSignature = url.substring(0, url.indexOf("&X-Goog-Signature="));
    String canonicalQuery = withoutSignature.substring(withoutSignature.indexOf('?') + 1);
    String path = URI.create(withoutSignature).getRawPath();
    Map<String, String> query = queryOf(url);

    String canonicalRequest =
        String.join(
            "\n", "GET", path, canonicalQuery, "host:" + HOST, "", "host", "UNSIGNED-PAYLOAD");
    String stringToSign =
        String.join(
            "\n",
            "GOOG4-RSA-SHA256",
            query.get("X-Goog-Date"),
            query.get("X-Goog-Credential").substring(EMAIL.length() + 1),
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8))));

    Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(publicKey);
    verifier.update(stringToSign.getBytes(StandardCharsets.UTF_8));
    return verifier.verify(
        HexFormat.of().parseHex(url.substring(url.indexOf("&X-Goog-Signature=") + 18)));
  }

  /** The query as Google would read it: every value decoded. */
  private static Map<String, String> queryOf(String url) {
    Map<String, String> query = new LinkedHashMap<>();
    for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
      int equals = pair.indexOf('=');
      query.put(
          java.net.URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
          java.net.URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
    }
    return query;
  }

  private static String serviceAccountKey(KeyPair keyPair) {
    String pem =
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(keyPair.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
    return "{\"type\":\"service_account\",\"client_email\":\""
        + EMAIL
        + "\",\"private_key\":\""
        + pem.replace("\n", "\\n")
        + "\"}";
  }
}
