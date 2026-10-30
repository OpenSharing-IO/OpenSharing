package io.opensharing.asset.table.signer;

import java.time.Duration;

/** Signs storage objects with one set of credentials, reusing one client for all of them. */
public interface SigningSession extends AutoCloseable {

  SignedUrl sign(String path, Duration ttl);

  @Override
  default void close() {}
}
