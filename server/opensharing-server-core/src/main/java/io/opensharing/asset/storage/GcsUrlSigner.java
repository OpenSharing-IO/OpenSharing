package io.opensharing.asset.storage;

import io.opensharing.catalog.StorageCredentials;
import io.opensharing.config.OpenSharingProperties;
import io.opensharing.http.ApiException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Signs Google storage urls with V4 query-string signing, so a recipient reads one object over plain
 * HTTPS.
 *
 * <p>This is the one signer whose signature does not come from the catalog's credentials. Google
 * signs with RSA over the request, so it takes a private key, and the access token a catalog vends
 * cannot be carried in a url — which is why the reference sharing server signs Google urls with a
 * service account key of its own, and why this does too. The consequence is worth stating plainly: a
 * url signed this way reaches whatever that service account may read, so the key should be one whose
 * access is no broader than what is shared, and the url's lifetime is the only thing bounding it.
 *
 * <p>Where no key is configured, this signer claims no scheme at all rather than accepting paths it
 * would fail on. Google storage is then served in dir access mode, which needs no key, and url mode
 * is never advertised for it.
 *
 * <p>The algorithm is written out here rather than pulled from Google's storage library, as the S3
 * one is: it is one request shape, and that library would bring gRPC and Guava into a server that
 * takes Google's connector shaded precisely to keep them out.
 */
@Component
public class GcsUrlSigner implements UrlSigner {

  private static final Logger log = LoggerFactory.getLogger(GcsUrlSigner.class);

  private static final String ALGORITHM = "GOOG4-RSA-SHA256";
  private static final String SCOPE_SUFFIX = "/auto/storage/goog4_request";
  private static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
  private static final String HOST = "storage.googleapis.com";
  private static final DateTimeFormatter GOOG_DATE =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter DATE_STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

  private final GcsSigningKey key;

  @Autowired
  public GcsUrlSigner(OpenSharingProperties properties) {
    this(
        GcsSigningKey.configured(
            properties.getStorage().getGcsServiceAccountKeyFile(),
            System.getenv("GOOGLE_APPLICATION_CREDENTIALS")));
  }

  GcsUrlSigner(GcsSigningKey key) {
    this.key = key;
    if (key == null) {
      log.info(
          "No Google service account key is configured, so no url is signed for gs paths: a table "
              + "on Google storage is served in dir access mode");
    }
  }

  /** None when there is no key, which is how url mode stops being offered for Google storage. */
  @Override
  public Set<String> schemes() {
    return key == null ? Set.of() : Set.of("gs");
  }

  @Override
  public SignedUrl sign(String path, StorageCredentials credentials, Duration ttl) {
    URI uri = URI.create(path);
    String bucket = uri.getHost();
    String object = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
    if (bucket == null || bucket.isBlank() || object.isBlank()) {
      throw ApiException.invalidParameter("'" + path + "' is not a Google storage object path");
    }
    Instant now = Instant.now();
    String scope = DATE_STAMP.format(now) + SCOPE_SUFFIX;

    Map<String, String> query = new TreeMap<>();
    query.put("X-Goog-Algorithm", ALGORITHM);
    query.put("X-Goog-Credential", key.clientEmail() + "/" + scope);
    query.put("X-Goog-Date", GOOG_DATE.format(now));
    query.put("X-Goog-Expires", Long.toString(ttl.toSeconds()));
    query.put("X-Goog-SignedHeaders", "host");

    String canonicalQuery = canonicalQuery(query);
    String resource = "/" + bucket + canonicalPath(object);
    String canonicalRequest =
        String.join(
            "\n",
            "GET",
            resource,
            canonicalQuery,
            "host:" + HOST,
            "",
            "host",
            UNSIGNED_PAYLOAD);
    String stringToSign =
        String.join(
            "\n", ALGORITHM, GOOG_DATE.format(now), scope, hex(sha256(canonicalRequest)));

    String url =
        "https://"
            + HOST
            + resource
            + "?"
            + canonicalQuery
            + "&X-Goog-Signature="
            + hex(rsaSha256(stringToSign));
    return new SignedUrl(url, now.plus(ttl));
  }

  private byte[] rsaSha256(String data) {
    try {
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(key.privateKey());
      signature.update(data.getBytes(StandardCharsets.UTF_8));
      return signature.sign();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("the configured key cannot sign a Google storage url", e);
    }
  }

  private static String canonicalQuery(Map<String, String> query) {
    return query.entrySet().stream()
        .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
        .collect(Collectors.joining("&"));
  }

  /** Every segment is encoded, but the separators stay, since they are part of the resource path. */
  private static String canonicalPath(String object) {
    StringBuilder path = new StringBuilder();
    for (String segment : object.split("/", -1)) {
      path.append('/').append(encode(segment));
    }
    return path.toString();
  }

  /** RFC 3986, as the V4 scheme requires, which is not what {@code URLEncoder} produces. */
  private static String encode(String value) {
    StringBuilder encoded = new StringBuilder(value.length());
    for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
      char c = (char) (b & 0xFF);
      if ((c >= 'A' && c <= 'Z')
          || (c >= 'a' && c <= 'z')
          || (c >= '0' && c <= '9')
          || c == '-'
          || c == '.'
          || c == '_'
          || c == '~') {
        encoded.append(c);
      } else {
        encoded.append('%').append(String.format(Locale.ROOT, "%02X", b & 0xFF));
      }
    }
    return encoded.toString();
  }

  private static byte[] sha256(String data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required to sign Google storage urls", e);
    }
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }
}
