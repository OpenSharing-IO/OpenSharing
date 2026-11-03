package io.opensharing.asset.table;

import java.util.List;

/**
 * Optional body for Query Table. Snapshot queries use version or timestamp; startingVersion, with
 * an optional endingVersion, returns data change files instead.
 */
public record QueryTableRequest(
    @Deprecated List<String> predicateHints,
    String jsonPredicateHints,
    Integer limitHint,
    Long version,
    String timestamp,
    Long startingVersion,
    Long endingVersion,
    Boolean includeHistoricalProtocol,
    String idempotencyKey,
    Boolean includeRefreshToken,
    String refreshToken) {

  static final QueryTableRequest EMPTY =
      new QueryTableRequest(null, null, null, null, null, null, null, null, null, null, null);

  public QueryTableRequest {
    refreshToken = refreshToken == null || refreshToken.isBlank() ? null : refreshToken;
  }
}
