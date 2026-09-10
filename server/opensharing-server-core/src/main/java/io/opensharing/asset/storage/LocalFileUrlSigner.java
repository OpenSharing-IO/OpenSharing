package io.opensharing.asset.storage;

import io.opensharing.catalog.StorageCredentials;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Hands back a {@code file:} url for a table on local disk, which is how the server can be exercised
 * end to end without a cloud account.
 *
 * <p>There is nothing to sign: the url only works for a client on this machine with access to the
 * path, so it grants no more than the filesystem already does. It is a development aid, and every
 * use says so in the log.
 */
@Component
public class LocalFileUrlSigner implements UrlSigner {

  private static final Logger log = LoggerFactory.getLogger(LocalFileUrlSigner.class);

  @Override
  public Set<String> schemes() {
    return Set.of("file");
  }

  @Override
  public SignedUrl sign(String path, StorageCredentials credentials, Duration ttl) {
    log.warn(
        "Serving {} as a file url, which only a client on this host can read: this is for local "
            + "development, not for sharing with a real recipient",
        path);
    String url = path.startsWith("file:") ? path : Path.of(path).toUri().toString();
    return new SignedUrl(url, Instant.now().plus(ttl));
  }
}
