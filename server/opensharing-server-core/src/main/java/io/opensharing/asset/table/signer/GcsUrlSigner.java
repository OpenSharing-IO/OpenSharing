package io.opensharing.asset.table.signer;

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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** V4 query-string signing for {@code gs://} objects. */
@Component
public class GcsUrlSigner implements UrlSigner {

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
  }

  @Override
  public Set<String> schemes() {
    return Set.of("gs");
  }

  @Override
  public SignedUrl sign(String path, StorageCredentials credentials, Duration ttl) {
    GcsSigningKey signingKey = keyFor(credentials);
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
    query.put("X-Goog-Credential", signingKey.clientEmail() + "/" + scope);
    query.put("X-Goog-Date", GOOG_DATE.format(now));
    query.put("X-Goog-Expires", Long.toString(ttl.toSeconds()));
    query.put("X-Goog-SignedHeaders", "host");

    String canonicalQuery = canonicalQuery(query);
    String resource = "/" + bucket + S3UrlSigner.canonicalPath(object);
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
        String.join("\n", ALGORITHM, GOOG_DATE.format(now), scope, hex(sha256(canonicalRequest)));
    String url =
        "https://"
            + HOST
            + resource
            + "?"
            + canonicalQuery
            + "&X-Goog-Signature="
            + hex(rsaSha256(signingKey, stringToSign));
    return new SignedUrl(url, now.plus(ttl));
  }

  private GcsSigningKey keyFor(StorageCredentials credentials) {
    if (key != null) {
      return key;
    }
    String file =
        credentials == null
            ? null
            : credentials.credentials().get(StorageCredentials.GOOGLE_SERVICE_ACCOUNT_KEY_FILE);
    GcsSigningKey fromCatalog = GcsSigningKey.fromFile(file);
    if (fromCatalog == null) {
      throw ApiException.notImplemented(
          "no Google service account key is configured to sign gs urls");
    }
    return fromCatalog;
  }

  private static byte[] rsaSha256(GcsSigningKey signingKey, String data) {
    try {
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(signingKey.privateKey());
      signature.update(data.getBytes(StandardCharsets.UTF_8));
      return signature.sign();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("the configured key cannot sign a Google storage url", e);
    }
  }

  private static String canonicalQuery(Map<String, String> query) {
    return query.entrySet().stream()
        .map(e -> S3UrlSigner.encode(e.getKey()) + "=" + S3UrlSigner.encode(e.getValue()))
        .collect(Collectors.joining("&"));
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
