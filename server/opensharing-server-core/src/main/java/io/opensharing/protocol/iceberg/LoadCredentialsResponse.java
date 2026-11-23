package io.opensharing.protocol.iceberg;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.opensharing.asset.table.iceberg.IcebergStorageCredential;
import java.util.List;

/**
 * Iceberg REST {@code loadCredentials}: read credentials for a table's location.
 *
 * <pre>{@code
 * {"storage-credentials": [{"prefix": "<table location>", "config": {...}}]}
 * }</pre>
 */
public record LoadCredentialsResponse(
    @JsonProperty("storage-credentials") List<IcebergStorageCredential> storageCredentials) {}
