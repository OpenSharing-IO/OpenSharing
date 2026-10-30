package io.opensharing.asset.table.signer;

import java.time.Instant;

/** A recipient-readable URL and the moment its signature expires. */
public record SignedUrl(String url, Instant expiration) {}
