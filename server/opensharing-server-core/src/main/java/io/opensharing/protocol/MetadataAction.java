package io.opensharing.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * A table's metadata as a Delta read response reports it.
 *
 * @param schemaString the schema as a JSON string, which the client deserializes itself
 * @param location the table root, which must be present for a table offering {@code dir} access
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MetadataAction(
    String id,
    String name,
    String description,
    String location,
    List<String> auxiliaryLocations,
    List<String> accessModes,
    Format format,
    String schemaString,
    List<String> partitionColumns,
    Map<String, String> configuration,
    Long version,
    Long size,
    Long numFiles)
    implements TableAction.Metadata {

  /** The encoding of the table's data files. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Format(String provider, Map<String, String> options) {}
}
