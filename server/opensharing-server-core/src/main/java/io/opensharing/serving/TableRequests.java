package io.opensharing.serving;

import io.opensharing.protocol.QueryTableRequest;

/**
 * What a client sent to a table read operation, before any format has had an opinion about it.
 *
 * <p>The values arrive unparsed on purpose. Which version a timestamp names, and what a capabilities
 * header may ask for, are questions only a format can answer, so the endpoint carries the request
 * across the seam intact rather than deciding on a format's behalf.
 */
public final class TableRequests {

  private TableRequests() {}

  public record Version(String startingTimestamp) {}

  public record Metadata(Long version, String timestamp, String capabilities) {}

  public record Query(QueryTableRequest body, String capabilities, String fileIdHash) {}

  public record Changes(
      Long startingVersion,
      String startingTimestamp,
      Long endingVersion,
      String endingTimestamp,
      boolean includeHistoricalMetadata,
      boolean includeHistoricalProtocol,
      String capabilities,
      String fileIdHash) {}
}
