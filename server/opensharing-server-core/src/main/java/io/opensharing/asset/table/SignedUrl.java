package io.opensharing.asset.table;

import java.time.Instant;

/** A recipient-readable URL and the moment its signature expires. */
record SignedUrl(String url, Instant expiration) {}
