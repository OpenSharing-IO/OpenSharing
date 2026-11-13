package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import java.util.List;
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

    // A table that does not record its Change Data Feed is PERMISSION_DENIED, in the table's
    // shared name.
    Response unrecorded = server.get(changes + "?startingVersion=0", bearer);
    assertEquals(403, unrecorded.status(), unrecorded.text());
    assertEquals(ErrorCodes.PERMISSION_DENIED, unrecorded.errorCode());
    assertTrue(
        unrecorded
            .text()
            .contains(
                "Unable to access change data feed for table-cdf.ops.events. CDF is not enabled on"
                    + " the original delta table for version 0."),
        unrecorded.text());

    // A boundary is a version or a timestamp, not both.
    Response twoStarts =
        server.get(changes + "?startingVersion=0&startingTimestamp=2024-01-01T00:00:00Z", bearer);
    assertEquals(400, twoStarts.status(), twoStarts.text());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, twoStarts.errorCode());
    assertTrue(
        twoStarts
            .text()
            .contains(
                "Multiple starting arguments provided for CDF read. Please provide one of either"
                    + " startingTimestamp or startingVersion."),
        twoStarts.text());
    Response twoEnds =
        server.get(
            changes + "?startingVersion=0&endingVersion=1&endingTimestamp=2024-01-01T00:00:00Z",
            bearer);
    assertEquals(400, twoEnds.status(), twoEnds.text());
    assertTrue(
        twoEnds
            .text()
            .contains(
                "Multiple ending arguments provided for CDF read. Please provide one of either"
                    + " endingTimestamp or endingVersion."),
        twoEnds.text());

    // A start is required, and must be a version or an ISO-8601 timestamp.
    for (String invalid : List.of("", "?startingTimestamp=yesterday", "?startingVersion=one")) {
      assertEquals(400, server.get(changes + invalid, bearer).status(), invalid);
    }

    // A table in an ungranted share is 404, not 403.
    String hiddenChanges = PROTOCOL + "/shares/hidden-cdf/schemas/sales/tables/customers/changes";
    Response hidden = server.get(hiddenChanges + "?startingVersion=0", bearer);
    assertEquals(404, hidden.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, hidden.errorCode());
  }
}
