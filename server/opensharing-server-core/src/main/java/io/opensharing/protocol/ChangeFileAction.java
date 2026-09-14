package io.opensharing.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * One file of a change data feed, sent as an {@code add}, {@code cdf} or {@code remove} line
 * depending on what it records. The three shapes are identical but for statistics, which only an
 * added file carries.
 *
 * @param version the table version this change belongs to, which a streaming reader tracks
 * @param timestamp the commit's timestamp, in epoch milliseconds
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChangeFileAction(
    String url,
    String id,
    Map<String, String> partitionValues,
    long size,
    long timestamp,
    long version,
    String stats,
    Long expirationTimestamp) {}
