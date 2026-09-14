package io.opensharing.asset.storage;

import io.opensharing.catalog.StorageCredentialKeys;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.http.ApiException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Signs Azure blob urls, which needs no signing at all: the user delegation SAS the catalog vends is
 * itself the signature, so the url is the blob's https address with that token appended.
 *
 * <p>Because the token is the grant, the url's lifetime is the SAS's lifetime — the requested ttl
 * cannot extend it, and shortening it would take re-issuing the SAS, which only the catalog can do.
 */
@Component
public class AzureSasUrlSigner implements UrlSigner {

  @Override
  public Set<String> schemes() {
    return Set.of("abfs", "abfss", "wasb", "wasbs");
  }

  @Override
  public SignedUrl sign(String path, StorageCredentials credentials, Duration ttl) {
    String sas = credentials.require(StorageCredentialKeys.SAS_TOKEN);
    Instant expiration =
        credentials.expiration() == null ? Instant.now().plus(ttl) : credentials.expiration();
    return new SignedUrl(httpsUrl(path) + "?" + (sas.startsWith("?") ? sas.substring(1) : sas), expiration);
  }

  /**
   * Rewrites {@code abfss://container@account.dfs.core.windows.net/path} into the blob endpoint a
   * plain HTTPS client can read.
   */
  private static String httpsUrl(String path) {
    URI uri = URI.create(path);
    String container = uri.getUserInfo();
    String host = uri.getHost();
    if (container == null || host == null) {
      throw ApiException.invalidParameter(
          "'" + path + "' is not an Azure path of the form scheme://container@account.host/blob");
    }
    return "https://" + host.replace(".dfs.", ".blob.") + "/" + container + uri.getPath();
  }
}
