package io.opensharing.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * Response of the Iceberg REST catalog {@code loadTable}: everything an engine needs to read the
 * table itself.
 *
 * <p>The metadata is relayed rather than described. It is the table's own metadata JSON, exactly as
 * it sits in storage, because that document belongs to the Iceberg spec and a client reads it with an
 * Iceberg library of its own. Modelling it here would only give this server a second, lossier opinion
 * about a format it does not implement.
 *
 * @param metadataLocation where that document lives, for a client that would rather fetch it itself
 * @param config properties a client merges into its file IO, which is where an engine that predates
 *     {@code storage-credentials} looks for vended credentials
 * @param storageCredentials one entry per storage prefix, each with the credentials for it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IcebergLoadTable(
    @JsonProperty("metadata-location") String metadataLocation,
    JsonNode metadata,
    Map<String, String> config,
    @JsonProperty("storage-credentials") List<Credential> storageCredentials) {

  /** Credentials scoped to one storage prefix, keyed as an Iceberg client expects to find them. */
  public record Credential(String prefix, Map<String, String> config) {}
}
