package io.opensharing.asset.table.signer;

import io.opensharing.asset.table.StorageLocations;
import io.opensharing.catalog.StorageCredentials;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Azure blob URLs carrying the catalog's SAS token. The token covers the catalog's whole storage
 * prefix, so each URL can read everything under it until the token expires.
 */
final class AzureSasUrlSigner implements UrlSigner {

  @Override
  public Set<String> schemes() {
    return Set.of("abfs", "abfss", "wasb", "wasbs");
  }

  @Override
  public SigningSession open(StorageCredentials credentials) {
    return (path, ttl) -> sign(path, credentials, ttl);
  }

  private static SignedUrl sign(String path, StorageCredentials credentials, Duration ttl) {
    String sas = credentials.require(StorageCredentials.SAS_TOKEN);
    Instant expiration =
        credentials.expiration() == null ? Instant.now().plus(ttl) : credentials.expiration();
    return new SignedUrl(httpsUrl(path) + (sas.startsWith("?") ? sas : "?" + sas), expiration);
  }

  private static String httpsUrl(String path) {
    StorageLocations.AzureBlob parsed = StorageLocations.azureBlob(path);
    return "https://"
        + parsed.host().replace(".dfs.", ".blob.")
        + "/"
        + parsed.container()
        + StorageLocations.encodePath(parsed.blob());
  }
}
