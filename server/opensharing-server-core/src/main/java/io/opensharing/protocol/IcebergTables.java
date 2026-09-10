package io.opensharing.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Response of the Iceberg REST catalog {@code listTables}: which tables a namespace holds. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IcebergTables(
    List<Identifier> identifiers, @JsonProperty("next-page-token") String nextPageToken) {

  /** A table named the way Iceberg names one: the namespace it sits in, and its own name. */
  public record Identifier(List<String> namespace, String name) {}
}
