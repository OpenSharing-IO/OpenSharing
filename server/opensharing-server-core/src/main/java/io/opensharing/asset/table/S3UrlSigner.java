package io.opensharing.asset.table;

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
 * SigV4 query-string signing for S3 (and s3a) object URLs, derived from catalog-vended AWS
 * credentials.
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
    String key = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
    if (bucket == null || bucket.isBlank() || key.isBlank()) {
      throw ApiException.invalidParameter("'" + path + "' is not an S3 object path");
    }
    String region = credentials.credentials().get(StorageCredentials.REGION);
    if (region == null || region.isBlank()) {
      region = defaultRegion;
    }
    Instant now = Instant.now();
    Instant expiration = now.plus(ttl);
    String host = bucket + ".s3." + region + ".amazonaws.com";

    Map<String, String> query = new TreeMap<>();
    query.put("X-Amz-Algorithm", ALGORITHM);
    query.put(
        "X-Amz-Credential",
        credentials.require(StorageCredentials.ACCESS_KEY_ID)
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
    String session = credentials.credentials().get(StorageCredentials.SESSION_TOKEN);
    if (session != null && !session.isBlank()) {
      query.put("X-Amz-Security-Token", session);
    }
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
                    credentials.require(StorageCredentials.SECRET_ACCESS_KEY),
                    DATE_STAMP.format(now),
                    region),
                stringToSign));
    String url =
        "https://"
            + host
            + canonicalPath(key)
            + "?"
            + canonicalQuery
            + "&X-Amz-Signature="
            + signature;
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

  static String canonicalPath(String key) {
    StringBuilder path = new StringBuilder();
    for (String segment : key.split("/", -1)) {
      path.append('/').append(encode(segment));
    }
    return path.toString();
  }

  static String encode(String value) {
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
