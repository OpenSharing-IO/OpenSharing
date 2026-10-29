package io.opensharing.asset.table.signer;

import io.opensharing.catalog.StorageCredentials;
import io.opensharing.http.ApiException;
import io.opensharing.util.ErrorCodes;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Picks a cloud signer for a storage path. */
public class UrlSigners {

  private final Map<String, UrlSigner> byScheme = new HashMap<>();

  /** Signs S3, Azure, and GCS paths. */
  public UrlSigners() {
    this(List.of(new S3UrlSigner(), new AzureSasUrlSigner(), new GcsUrlSigner()));
  }

  UrlSigners(List<UrlSigner> signers) {
    for (UrlSigner signer : signers) {
      signer.schemes().forEach(scheme -> byScheme.put(scheme.toLowerCase(Locale.ROOT), signer));
    }
  }

  /**
   * Signs paths with {@code credentials}. Each cloud's signer is opened on its first path and
   * reused for the rest, and closing the session closes them.
   */
  public SigningSession open(StorageCredentials credentials) {
    Map<UrlSigner, SigningSession> opened = new HashMap<>();
    return new SigningSession() {
      @Override
      public SignedUrl sign(String path, Duration ttl) {
        UrlSigner signer = signerFor(path);
        Duration capped = capped(credentials, ttl);
        return opened.computeIfAbsent(signer, s -> s.open(credentials)).sign(path, capped);
      }

      @Override
      public void close() {
        RuntimeException failure = null;
        for (SigningSession session : opened.values()) {
          try {
            session.close();
          } catch (RuntimeException e) {
            if (failure == null) {
              failure = e;
            } else {
              failure.addSuppressed(e);
            }
          }
        }
        if (failure != null) {
          throw failure;
        }
      }
    };
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
          502,
          ErrorCodes.CATALOG_ERROR,
          "the catalog vended credentials that had already expired, so no url can be signed");
    }
    return untilExpiry.compareTo(ttl) < 0 ? untilExpiry : ttl;
  }
}
