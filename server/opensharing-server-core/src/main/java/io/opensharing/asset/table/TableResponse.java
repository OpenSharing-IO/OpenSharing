package io.opensharing.asset.table;

/**
 * Protocol table listed under a share schema:
 *
 * <pre>{@code
 * {"name": "<table name>", "schema": "<schema name>", "share": "<share name>",
 *  "shareId": "<share id>", "id": "<catalog table id>"}
 * }</pre>
 */
public record TableResponse(String name, String schema, String share, String shareId, String id) {}
