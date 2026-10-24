package io.opensharing.asset.table.signer;

import io.opensharing.catalog.StorageCredentials;
import java.util.Set;

/** Signs storage objects for credential-free, read-only HTTPS access. */
interface UrlSigner {

  Set<String> schemes();

  SigningSession open(StorageCredentials credentials);
}
