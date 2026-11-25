package io.opensharing.protocol.iceberg;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Iceberg REST {@code loadTable}: the table's current metadata.
 *
 * <pre>{@code
 * {"metadata-location": "<metadata JSON location>", "metadata": {<metadata JSON>}}
 * }</pre>
 */
public record LoadTableResponse(
    @JsonProperty("metadata-location") String metadataLocation, ObjectNode metadata) {}
