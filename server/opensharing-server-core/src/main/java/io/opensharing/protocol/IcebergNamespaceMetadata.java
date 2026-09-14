package io.opensharing.protocol;

import java.util.List;
import java.util.Map;

/**
 * Response of the Iceberg REST catalog {@code loadNamespaceMetadata}: that the namespace exists, and
 * what is known about it.
 *
 * <p>The properties are empty, because a schema in a share is not an object a provider describes:
 * it is the level the aliases of the shared tables have in common.
 */
public record IcebergNamespaceMetadata(List<String> namespace, Map<String, String> properties) {}
