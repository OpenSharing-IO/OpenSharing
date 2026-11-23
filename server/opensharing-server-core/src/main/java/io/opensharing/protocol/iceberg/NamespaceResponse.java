package io.opensharing.protocol.iceberg;

import java.util.List;
import java.util.Map;

/**
 * Iceberg REST {@code loadNamespaceMetadata}:
 *
 * <pre>{@code
 * {"namespace": ["<schema>"], "properties": {}}
 * }</pre>
 */
public record NamespaceResponse(List<String> namespace, Map<String, String> properties) {}
