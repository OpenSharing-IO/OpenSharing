package io.opensharing.protocol.iceberg;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Iceberg REST {@code listNamespaces}: one page of namespaces, each a list of its levels.
 *
 * <pre>{@code
 * {"namespaces": [["<schema>"], ...], "next-page-token": "<token>" or null on the last page}
 * }</pre>
 */
public record NamespacesResponse(
    List<List<String>> namespaces,
    @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("next-page-token")
        String nextPageToken) {}
