package io.opensharing.asset.table.signer;

import io.opensharing.catalog.StorageCredentials;
import io.opensharing.util.StoragePaths;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * GCS URLs carrying the catalog's OAuth token as an {@code access_token} query parameter. The token
 * covers the catalog's whole storage prefix, so each URL can read everything under it until the
 * token expires.
 */
final class GcsUrlSigner implements UrlSigner {

  @Override
  public Set<String> schemes() {
    return Set.of("gs");
  }

  @Override
  public SigningSession open(StorageCredentials credentials) {
    return (path, ttl) -> sign(path, credentials, ttl);
  }

  private static SignedUrl sign(String path, StorageCredentials credentials, Duration ttl) {
    String token = credentials.require(StorageCredentials.OAUTH_TOKEN);
    String[] bucketAndObject = StoragePaths.bucketAndKey(path);
    Instant expiration =
        credentials.expiration() == null ? Instant.now().plus(ttl) : credentials.expiration();
    return new SignedUrl(
        "https://storage.googleapis.com/"
            + bucketAndObject[0]
            + StoragePaths.encodePath(bucketAndObject[1])
            + "?access_token="
            + URLEncoder.encode(token, StandardCharsets.UTF_8),
        expiration);
  }
}
