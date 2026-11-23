package io.opensharing.protocol.iceberg;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Iceberg REST {@code listTables}: one page of a namespace's tables.
 *
 * <pre>{@code
 * {"identifiers": [{"namespace": ["<schema>"], "name": "<table>"}, ...],
 *  "next-page-token": "<token>" or null on the last page}
 * }</pre>
 */
public record TablesResponse(
    List<Identifier> identifiers,
    @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("next-page-token")
        String nextPageToken) {

  /** A table by its namespace's levels and its name. */
  public record Identifier(List<String> namespace, String name) {}
}
