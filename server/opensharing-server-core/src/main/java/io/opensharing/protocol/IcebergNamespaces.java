package io.opensharing.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Response of the Iceberg REST catalog {@code listNamespaces}.
 *
 * <p>A namespace is a list because Iceberg's are multi-level; a share's are one level deep, so each
 * inner list here holds a single schema name.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IcebergNamespaces(
    List<List<String>> namespaces, @JsonProperty("next-page-token") String nextPageToken) {}
