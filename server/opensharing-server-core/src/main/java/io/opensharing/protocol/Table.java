package io.opensharing.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A shared table, as {@code spec/protocols/TABLES.md} and the Delta Sharing protocol define it.
 *
 * <p>{@code location} is where the table's log and data live, and must be present whenever
 * {@code accessModes} includes {@code dir}, since that is all a directory-access client is given to
 * work from.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Table(
    String name,
    String schema,
    String share,
    String shareId,
    String id,
    String location,
    List<String> auxiliaryLocations,
    List<String> accessModes) {}
