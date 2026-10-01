package io.opensharing.asset.table.signer;

import io.opensharing.catalog.StorageCredentials;
import java.time.Duration;
import java.util.Set;

/** Signs one storage object for credential-free, read-only HTTPS access. */
interface UrlSigner {

  Set<String> schemes();

  SignedUrl sign(String path, StorageCredentials credentials, Duration ttl);
}
