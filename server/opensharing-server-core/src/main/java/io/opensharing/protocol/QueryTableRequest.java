package io.opensharing.protocol;

import java.util.List;

/**
 * The body of a table query. Every field is optional, and an empty object asks for the whole latest
 * snapshot.
 *
 * <p>The hints are what their name says: a server may narrow the files it returns by them, and a
 * client must apply its own filter to the response regardless. This server does not narrow, so it
 * accepts them and returns every file of the version asked for.
 *
 * @param version a version to read instead of the latest
 * @param timestamp the state at a moment, as an ISO-8601 instant, exclusive with {@code version}
 * @param startingVersion asks for data change files since that version rather than a snapshot
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
    String idempotencyKey) {

  public static QueryTableRequest snapshot() {
    return new QueryTableRequest(null, null, null, null, null, null, null, null, null);
  }
}
