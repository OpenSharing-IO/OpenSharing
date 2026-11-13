package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import org.junit.jupiter.api.Test;

/**
 * Query Table Changes requests on local Delta logs, which do not record a Change Data Feed. Feeds
 * themselves are read in {@code DeltaTableQueryReaderTest} and on the cloud test tables.
 */
class ProtocolTableChangesTest extends LocalDeltaTableSupport {

  @Test
  void validatesChangeDataFeedRequests() {
    createShare("table-cdf");
    addObject("table-cdf", "TABLE", "main.ops.events", "ops.events");
    createShare("hidden-cdf");
    addObject("hidden-cdf", "TABLE", "main.sales.customers", "sales.customers");
    String bearer = createAndActivateRecipient("table-cdf-partner");
    grant("table-cdf", "table-cdf-partner");
    String changes = PROTOCOL + "/shares/TABLE-CDF/schemas/OPS/tables/EVENTS/changes";

    // A table that does not record its Change Data Feed is the source table's own state, and the
    // recipient is told to contact the data provider.
    assertError(
        403,
        ErrorCodes.PERMISSION_DENIED,
        "Unable to access change data feed for table-cdf.ops.events. CDF is not enabled on the"
            + " original delta table for version 0. Please contact your data provider.",
        server.get(changes + "?startingVersion=0", bearer));

    // A start is required, once, and must be a version or an ISO-8601 timestamp.
    assertError(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "No startingVersion or startingTimestamp provided for CDF read. Set one of them on the"
            + " request's cdfOptions to bound the CDC range.",
        server.get(changes, bearer));
    assertError(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "startingTimestamp must be an ISO-8601 UTC timestamp",
        server.get(changes + "?startingTimestamp=yesterday", bearer));
    assertError(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "startingVersion is not a valid number.",
        server.get(changes + "?startingVersion=one", bearer));
    assertError(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "endingVersion is not a valid number.",
        server.get(changes + "?startingVersion=0&endingVersion=last", bearer));
    assertError(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "Multiple starting arguments provided for CDF read. Please provide one of either"
            + " startingTimestamp or startingVersion.",
        server.get(changes + "?startingVersion=0&startingTimestamp=2024-01-01T00:00:00Z", bearer));
    assertError(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "Multiple ending arguments provided for CDF read. Please provide one of either"
            + " endingTimestamp or endingVersion.",
        server.get(
            changes + "?startingVersion=0&endingVersion=1&endingTimestamp=2024-01-01T00:00:00Z",
            bearer));

    // A negative bound, and a range that ends before it starts.
    assertError(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "startingVersion cannot be negative.",
        server.get(changes + "?startingVersion=-1", bearer));
    assertError(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "endingVersion cannot be negative.",
        server.get(changes + "?startingVersion=0&endingVersion=-1", bearer));
    assertError(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "CDF range from startingVersion 1 to endingVersion 0 was invalid. End cannot be before"
            + " start.",
        server.get(changes + "?startingVersion=1&endingVersion=0", bearer));

    // A bound after the latest version, or the latest commit time.
    assertError(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "Provided Start version(9) for reading change data is invalid. Start version cannot be"
            + " greater than the latest version of the table(1).",
        server.get(changes + "?startingVersion=9", bearer));
    assertError(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "Provided End version(9) for reading change data is invalid. End version cannot be"
            + " greater than the latest version of the table(1).",
        server.get(changes + "?startingVersion=0&endingVersion=9", bearer));
    assertError(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "The provided timestamp(2100-01-01T00:00:00Z) is after the latest table version"
            + " available to this table (2024-01-01T01:00:00Z). Please use a timestamp less than"
            + " or equal to 2024-01-01T01:00:00Z",
        server.get(changes + "?startingVersion=0&endingTimestamp=2100-01-01T00:00:00Z", bearer));

    // A start timestamp before the first commit starts at version 0, and an end timestamp before
    // it is Kernel's report, without the storage path.
    assertError(
        403,
        ErrorCodes.PERMISSION_DENIED,
        "Unable to access change data feed for table-cdf.ops.events. CDF is not enabled on the"
            + " original delta table for version 0. Please contact your data provider.",
        server.get(changes + "?startingTimestamp=2000-01-01T00:00:00Z", bearer));
    Response early =
        server.get(changes + "?startingVersion=0&endingTimestamp=2000-01-01T00:00:00Z", bearer);
    assertError(
        400,
        ErrorCodes.BAD_REQUEST,
        "The provided timestamp 946684800000 ms (2000-01-01T00:00:00Z) is before the earliest"
            + " available version 0. Please use a timestamp greater than or equal to"
            + " 1704067200000 ms (2024-01-01T00:00:00Z).",
        early);
    assertNoStoragePath(early);

    // A table in an ungranted share is 404, not 403.
    String hiddenChanges = PROTOCOL + "/shares/hidden-cdf/schemas/sales/tables/customers/changes";
    Response hidden = server.get(hiddenChanges + "?startingVersion=0", bearer);
    assertEquals(404, hidden.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, hidden.errorCode());
  }
}
