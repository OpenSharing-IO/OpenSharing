package io.opensharing.asset.storage;

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

/** Picks the signer for a path, and says plainly when no signer can serve one. */
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

  /**
   * Whether a path is one this build could hand out a url for, which decides whether url access mode
   * is offered at all: a table advertising a mode that refuses every one of its files would be worse
   * than one that never offered it. Asked of the table's own location, since a table's files are
   * under it and share its scheme.
   */
  public boolean canSign(String path) {
    return path != null && byScheme.containsKey(scheme(path));
  }

  private UrlSigner signerFor(String path) {
    String scheme = scheme(path);
    UrlSigner signer = byScheme.get(scheme);
    if (signer == null) {
      throw ApiException.notImplemented(
          "this build cannot sign urls for '"
              + scheme
              + "' storage; use dir access mode and temporary-table-credentials to read the table");
    }
    return signer;
  }

  private static String scheme(String path) {
    int separator = path.indexOf("://");
    // A bare path is a local one, which is how a table on disk is addressed.
    return separator < 0 ? "file" : path.substring(0, separator).toLowerCase(Locale.ROOT);
  }

  /**
   * A url must not outlive the grant it was derived from, whatever ttl was asked for: the credentials
   * stop working at their expiry and the url would only 403 after it.
   *
   * <p>A grant that has already run out has nothing left to cap to. That is the catalog's doing,
   * not the recipient's, so it is said as a bad gateway rather than handed over as a url that is
   * dead on arrival — which is what a url stamped with the full ttl would be.
   */
  private static Duration capped(StorageCredentials credentials, Duration ttl) {
    if (credentials == null || credentials.expiration() == null) {
      return ttl;
    }
    Duration untilExpiry = Duration.between(Instant.now(), credentials.expiration());
    if (untilExpiry.isNegative() || untilExpiry.isZero()) {
      throw new ApiException(
          HttpStatus.BAD_GATEWAY,
          ErrorCodes.INTERNAL_ERROR,
          "the catalog vended credentials that had already expired, so no url can be signed from "
              + "them");
    }
    return untilExpiry.compareTo(ttl) < 0 ? untilExpiry : ttl;
  }
}
