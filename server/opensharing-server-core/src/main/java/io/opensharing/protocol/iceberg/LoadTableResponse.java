package io.opensharing.protocol.iceberg;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opensharing.asset.table.iceberg.IcebergStorageCredential;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Iceberg REST {@code loadTable}: the table's current metadata, and credentials to read it with
 * when the client asked for them.
 *
 * <pre>{@code
 * {"metadata-location": "<metadata JSON location>", "metadata": {<metadata JSON>},
 *  "config": {"s3.access-key-id": "<key id>", ...},
 *  "storage-credentials": [{"prefix": "<table location>", "config": {...}}]}
 * }</pre>
 *
 * @param config the first credential's file IO properties and its refresh endpoint, for clients
 *     that predate {@code storage-credentials}; null when none were asked for
 */
public record LoadTableResponse(
    @JsonProperty("metadata-location") String metadataLocation,
    ObjectNode metadata,
    Map<String, String> config,
    @JsonProperty("storage-credentials") List<IcebergStorageCredential> storageCredentials) {
  @Override
  public String toString() {
    return "LoadTableResponse[metadataLocation="
        + metadataLocation
        + ", configKeys="
        + (config == null ? null : new TreeSet<>(config.keySet()))
        + ", credentialPrefixes="
        + (storageCredentials == null
            ? null
            : storageCredentials.stream().map(IcebergStorageCredential::prefix).toList())
        + "]";
  }
}
