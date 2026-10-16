package io.opensharing.asset.table;

import java.util.List;

/**
 * Protocol table listed under a share schema:
 *
 * <pre>{@code
 * {"name": "<table name>", "schema": "<schema name>", "share": "<share name>",
 *  "shareId": "<share id>", "id": "<catalog table id>", "location": "<storage location>",
 *  "auxiliaryLocations": ["<storage location>", ...], "accessModes": ["url", "dir"]}
 * }</pre>
 */
public record TableResponse(
    String name,
    String schema,
    String share,
    String shareId,
    String id,
    String location,
    List<String> auxiliaryLocations,
    List<String> accessModes) {}
