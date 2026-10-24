package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Query Table Version through the local catalog and Kernel, on local Delta logs. */
class ProtocolTableVersionTest extends LocalDeltaTableSupport {

  @Test
  void queriesAGrantedTableVersion() {
    createShare("table-version");
    addObject("table-version", "TABLE", "main.sales.orders", "sales.orders");
    addObject("table-version", "SCHEMA", "main.hr", "hr");
    createShare("hidden-version");
    addObject("hidden-version", "TABLE", "main.sales.customers", "sales.customers");
    String bearer = createAndActivateRecipient("table-version-partner");
    grant("table-version", "table-version-partner");

    // The latest version, as a header with no body; lookups are case-insensitive.
    String orders = PROTOCOL + "/shares/TABLE-VERSION/schemas/SALES/tables/ORDERS/version";
    Response latest = version(orders, bearer, null);
    assertEquals(200, latest.status());
    assertEquals("2", latest.headers().get("Delta-Table-Version"));
    assertTrue(latest.body().isMissingNode());

    // A table under a shared schema: the earliest version committed at or after the timestamp.
    String salaries = PROTOCOL + "/shares/table-version/schemas/hr/tables/SALARIES/version";
    assertVersion("0", version(salaries, bearer, "2023-12-31T00:00:00Z"));
    assertVersion("1", version(salaries, bearer, "2024-01-01T01:00:00Z"));
    assertVersion("2", version(salaries, bearer, "2024-01-01T01:30:00Z"));
    assertVersion("3", version(salaries, bearer, null));

    Response afterLatest = version(salaries, bearer, "2024-01-01T03:00:01Z");
    assertEquals(400, afterLatest.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, afterLatest.errorCode());
    Response invalid = version(orders, bearer, "not-a-timestamp");
    assertEquals(400, invalid.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, invalid.errorCode());

    // Missing tables and ungranted shares are both reported as missing.
    Response missing =
        version(PROTOCOL + "/shares/table-version/schemas/hr/tables/missing/version", bearer, null);
    assertEquals(404, missing.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, missing.errorCode());
    Response hidden =
        version(
            PROTOCOL + "/shares/hidden-version/schemas/sales/tables/customers/version",
            bearer,
            null);
    assertEquals(404, hidden.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, hidden.errorCode());
  }

  private static Response version(String path, String bearer, String startingTimestamp) {
    String query =
        startingTimestamp == null
            ? ""
            : "?startingTimestamp=" + URLEncoder.encode(startingTimestamp, StandardCharsets.UTF_8);
    return server.get(path + query, bearer);
  }

  private static void assertVersion(String expected, Response response) {
    assertEquals(200, response.status());
    assertEquals(expected, response.headers().get("Delta-Table-Version"));
  }
}
