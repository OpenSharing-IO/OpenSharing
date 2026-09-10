package io.opensharing.asset.storage;

import java.time.Instant;

/** A url a recipient can read, and the moment it stops working. */
public record SignedUrl(String url, Instant expiration) {}
