package io.opensharing.protocol;

import java.util.List;
import java.util.Map;

/**
 * Response of the Iceberg REST catalog {@code GET /v1/config} handshake.
 *
 * @param overrides carries {@code prefix}, the path prefix the client must use for every later call,
 *     which is how a share is selected
 * @param endpoints the catalog operations this server implements
 */
public record IcebergConfig(
    Map<String, String> defaults, Map<String, String> overrides, List<String> endpoints) {}
