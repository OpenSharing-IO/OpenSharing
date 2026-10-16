package io.opensharing.asset.schema;

/**
 * Protocol schema listed under a share:
 *
 * <pre>{@code
 * {"name": "<schema name>", "share": "<share name>"}
 * }</pre>
 */
public record SchemaResponse(String name, String share) {}
