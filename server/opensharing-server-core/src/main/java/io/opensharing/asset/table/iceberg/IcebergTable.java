package io.opensharing.asset.table.iceberg;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What a recipient needs to read an Iceberg table: its current metadata.
 *
 * @param metadataLocation where the table's current metadata JSON is
 * @param metadata that metadata JSON, as stored
 */
public record IcebergTable(String metadataLocation, ObjectNode metadata) {}
