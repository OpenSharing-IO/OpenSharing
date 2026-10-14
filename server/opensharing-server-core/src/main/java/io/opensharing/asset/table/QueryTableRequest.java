package io.opensharing.asset.table;

import java.util.List;

/**
 * Optional body for Query Table. Snapshot queries use version or timestamp; streaming is not
 * implemented.
 */
public record QueryTableRequest(
    // Deprecated by the protocol in favor of jsonPredicateHints; only logged, never used to filter.
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
