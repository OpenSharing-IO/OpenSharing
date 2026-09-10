package io.opensharing.asset.storage;

import io.opensharing.catalog.StorageCredentialKeys;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.config.OpenSharingProperties;
import io.opensharing.http.ApiException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Signs S3 urls with SigV4 query-string signing, so a recipient reads one object over plain HTTPS.
 *
 * <p>The signature is derived from the temporary credentials the catalog minted, which is why a url
 * can never outlive or outreach them: it carries their session token and dies with them.
 *
 * <p>The algorithm is written out here rather than pulled from the AWS SDK, which would add a large
 * dependency for one request shape we never send.
 */
@Component
public class S3UrlSigner implements UrlSigner {

  private static final String ALGORITHM = "AWS4-HMAC-SHA256";
  private static final String SERVICE = "s3";
  private static final String TERMINATOR = "aws4_request";
  private static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
  private static final DateTimeFormatter AMZ_DATE =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter DATE_STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

  private final String defaultRegion;

  public S3UrlSigner(OpenSharingProperties properties) {
    this.defaultRegion = properties.getStorage().getS3Region();
  }

  @Override
  public Set<String> schemes() {
    return Set.of("s3", "s3a", "s3n");
  }

  @Override
  public SignedUrl sign(String path, StorageCredentials credentials, Duration ttl) {
    URI uri = URI.create(path);
    String bucket = uri.getHost();
    String key = uri.getPath().startsWith("/") ? uri.getPath().substring(1) : uri.getPath();
    if (bucket == null || bucket.isBlank() || key.isBlank()) {
      throw ApiException.invalidParameter("'" + path + "' is not an S3 object path");
    }
    String region =
        credentials.credentials().getOrDefault(StorageCredentialKeys.REGION, defaultRegion);
    Instant now = Instant.now();
    Instant expiration = now.plus(ttl);
    String host = bucket + ".s3." + region + ".amazonaws.com";

    Map<String, String> query = new TreeMap<>();
    query.put("X-Amz-Algorithm", ALGORITHM);
    query.put(
        "X-Amz-Credential",
        credentials.require(StorageCredentialKeys.ACCESS_KEY_ID)
            + "/"
            + DATE_STAMP.format(now)
            + "/"
            + region
            + "/"
            + SERVICE
            + "/"
            + TERMINATOR);
    query.put("X-Amz-Date", AMZ_DATE.format(now));
    query.put("X-Amz-Expires", Long.toString(ttl.toSeconds()));
    query.put("X-Amz-Security-Token", credentials.require(StorageCredentialKeys.SESSION_TOKEN));
    query.put("X-Amz-SignedHeaders", "host");

    String canonicalQuery = canonicalQuery(query);
    String canonicalRequest =
        String.join(
            "\n",
            "GET",
            canonicalPath(key),
            canonicalQuery,
            "host:" + host,
            "",
            "host",
            UNSIGNED_PAYLOAD);
    String stringToSign =
        String.join(
            "\n",
            ALGORITHM,
            AMZ_DATE.format(now),
            DATE_STAMP.format(now) + "/" + region + "/" + SERVICE + "/" + TERMINATOR,
            hex(sha256(canonicalRequest)));
    String signature =
        hex(
            hmac(
                signingKey(
                    credentials.require(StorageCredentialKeys.SECRET_ACCESS_KEY),
                    DATE_STAMP.format(now),
                    region),
                stringToSign));

    String url =
        "https://" + host + canonicalPath(key) + "?" + canonicalQuery + "&X-Amz-Signature=" + signature;
    return new SignedUrl(url, expiration);
  }

  private static byte[] signingKey(String secretKey, String dateStamp, String region) {
    byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), dateStamp);
    key = hmac(key, region);
    key = hmac(key, SERVICE);
    return hmac(key, TERMINATOR);
  }

  private static String canonicalQuery(Map<String, String> query) {
    return query.entrySet().stream()
        .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
        .collect(Collectors.joining("&"));
  }

  /** Every segment is encoded, but the separators stay, since they are part of the resource path. */
  private static String canonicalPath(String key) {
    StringBuilder path = new StringBuilder();
    for (String segment : key.split("/", -1)) {
      path.append('/').append(encode(segment));
    }
    return path.toString();
  }

  /**
   * SigV4 requires RFC 3986 encoding, which differs from form encoding in three places, so it is
   * spelled out rather than delegated to {@code URLEncoder}.
   */
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

  private static byte[] hmac(byte[] key, String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HmacSHA256 is required to sign S3 urls", e);
    }
  }

  private static byte[] sha256(String data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required to sign S3 urls", e);
    }
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }
}
