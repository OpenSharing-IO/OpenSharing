package io.opensharing.asset.table;

import java.util.List;

/**
 * Optional body for Query Table. Snapshot queries use version or timestamp; streaming is not
 * implemented.
 */
public record QueryTableRequest(
    List<String> predicateHints,
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
}
