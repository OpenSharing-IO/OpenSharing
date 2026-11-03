package io.opensharing.asset.table;

/**
 * Query parameters of Query Table Changes. The range starts at {@code startingVersion} or {@code
 * startingTimestamp}, and ends at {@code endingVersion}, {@code endingTimestamp}, or the latest
 * version; timestamps are ISO-8601.
 */
public record TableChangesRequest(
    Long startingVersion,
    String startingTimestamp,
    Long endingVersion,
    String endingTimestamp,
    Boolean includeHistoricalMetadata,
    Boolean includeHistoricalProtocol) {}
