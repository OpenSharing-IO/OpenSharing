package io.opensharing.asset.table;

/**
 * Protocol {@code GET /shares/{share}/schemas/{schema}/tables/{table}}:
 *
 * <pre>{@code
 * {"name": "<table name>", "schema": "<schema name>", "share": "<share name>",
 *  "shareId": "<share id>", "id": "<catalog table id>", "format": "<table format>"}
 * }</pre>
 */
public record GetTableResponse(
    String name, String schema, String share, String shareId, String id, String format) {}
