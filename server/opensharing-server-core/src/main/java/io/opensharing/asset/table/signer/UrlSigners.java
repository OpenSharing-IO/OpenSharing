package io.opensharing.asset.table.signer;

import io.opensharing.catalog.StorageCredentials;
import io.opensharing.http.ApiException;
import io.opensharing.http.ErrorCodes;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Picks a cloud signer for a storage path. */
@Component
public class UrlSigners {

  private final Map<String, UrlSigner> byScheme = new HashMap<>();

  public UrlSigners(List<UrlSigner> signers) {
    for (UrlSigner signer : signers) {
      signer.schemes().forEach(scheme -> byScheme.put(scheme.toLowerCase(Locale.ROOT), signer));
    }
  }

  public SignedUrl sign(String path, StorageCredentials credentials, Duration ttl) {
    return signerFor(path).sign(path, credentials, capped(credentials, ttl));
  }

  private UrlSigner signerFor(String path) {
    String scheme = scheme(path);
    UrlSigner signer = byScheme.get(scheme);
    if (signer == null) {
      throw ApiException.notImplemented("this build cannot sign urls for '" + scheme + "' storage");
    }
    return signer;
  }

  static String scheme(String path) {
    int separator = path.indexOf("://");
    return separator < 0 ? "file" : path.substring(0, separator).toLowerCase(Locale.ROOT);
  }

  private static Duration capped(StorageCredentials credentials, Duration ttl) {
    if (credentials == null || credentials.expiration() == null) {
      return ttl;
    }
    Duration untilExpiry = Duration.between(Instant.now(), credentials.expiration());
    if (untilExpiry.isNegative() || untilExpiry.isZero()) {
      throw new ApiException(
          HttpStatus.BAD_GATEWAY,
          ErrorCodes.INTERNAL_ERROR,
          "the catalog vended credentials that had already expired, so no url can be signed from them");
    }
    return untilExpiry.compareTo(ttl) < 0 ? untilExpiry : ttl;
  }
}
