package io.opensharing.asset.table;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Protocol table listed under a share schema. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TableResponse(
    String name,
    String schema,
    String share,
    String shareId,
    String id,
    String location,
    List<String> auxiliaryLocations,
    List<String> accessModes,
    String format) {}
