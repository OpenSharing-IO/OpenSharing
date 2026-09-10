package io.opensharing.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * What the table is, as delta response format states it: the log's own metadata action, wrapped with
 * the few things a recipient learns from the sharing server rather than from the log.
 *
 * <p>Where the table lives and how it may be reached are outside the log, which is why they sit on
 * the wrapper beside the action instead of inside it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeltaMetadataAction(
    Metadata deltaMetadata,
    Long version,
    Long size,
    Long numFiles,
    String location,
    List<String> auxiliaryLocations,
    List<String> accessModes)
    implements TableAction.Metadata {

  /** The metadata action of the Delta log, which a Delta library reads as its own. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Metadata(
      String id,
      String name,
      String description,
      MetadataAction.Format format,
      String schemaString,
      List<String> partitionColumns,
      Map<String, String> configuration,
      Long createdTime) {}
}
