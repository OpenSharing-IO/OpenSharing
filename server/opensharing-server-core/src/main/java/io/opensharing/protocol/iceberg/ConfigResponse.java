package io.opensharing.protocol.iceberg;

import java.util.List;
import java.util.Map;

/**
 * Iceberg REST {@code getConfig}: the properties a client applies to its catalog, and the endpoints
 * this catalog serves.
 *
 * <pre>{@code
 * {"defaults": {},
 *  "overrides": {"prefix": "shares/<share>"},
 *  "endpoints": ["GET /v1/{prefix}/namespaces", ...]}
 * }</pre>
 */
public record ConfigResponse(
    Map<String, String> defaults, Map<String, String> overrides, List<String> endpoints) {}
