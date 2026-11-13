package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Query Table Version through the local catalog and Kernel, on local Delta logs. */
class ProtocolTableVersionTest extends LocalDeltaTableSupport {

  @Test
  void queriesAGrantedTableVersion() {
    createShare("table-version");
    addObject("table-version", "TABLE", "main.sales.orders", "sales.orders");
    addObject("table-version", "TABLE", "main.hr.salaries", "hr.salaries");
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

    // With a timestamp, the earliest version committed at or after it.
    String salaries = PROTOCOL + "/shares/table-version/schemas/hr/tables/SALARIES/version";
    assertVersion("0", version(salaries, bearer, "2023-12-31T00:00:00Z"));
    assertVersion("1", version(salaries, bearer, "2024-01-01T01:00:00Z"));
    assertVersion("2", version(salaries, bearer, "2024-01-01T01:30:00Z"));
    assertVersion("3", version(salaries, bearer, null));

    // A timestamp after the latest commit is Kernel's report, in Kernel's words.
    Response afterLatest = version(salaries, bearer, "2024-01-01T03:00:01Z");
    assertError(
        400,
        ErrorCodes.BAD_REQUEST,
        "The provided timestamp 1704078001000 ms (2024-01-01T03:00:01Z) is after the latest"
            + " available version 3. Please use a timestamp less than or equal to 1704078000000"
            + " ms (2024-01-01T03:00:00Z).",
        afterLatest);
    Response invalid = version(orders, bearer, "not-a-timestamp");
    assertError(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "startingTimestamp must be an ISO-8601 UTC timestamp",
        invalid);

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

  @Test
  void answersATableKernelCannotReadWithoutItsStoragePath() {
    createShare("unreadable-version");
    addObject("unreadable-version", "TABLE", "main.lake.unwritten", "lake.unwritten");
    addObject("unreadable-version", "TABLE", "main.lake.raw", "lake.raw");
    addObject("unreadable-version", "TABLE", "main.lake.future", "lake.future");
    addObject("unreadable-version", "TABLE", "main.lake.gapped", "lake.gapped");
    addObject("unreadable-version", "TABLE", "main.lake.noncontiguous", "lake.noncontiguous");
    addObject("unreadable-version", "TABLE", "main.lake.invalidlog", "lake.invalidlog");
    String bearer = createAndActivateRecipient("unreadable-version-partner");
    grant("unreadable-version", "unreadable-version-partner");
    String lake = PROTOCOL + "/shares/unreadable-version/schemas/lake/tables/";

    // A Delta table with no log is the source table's own state: the recipient is told to contact
    // the data provider, and is given the table's name as the share gives it.
    Response unwritten = version(lake + "unwritten/version", bearer, null);
    assertError(
        400,
        ErrorCodes.BAD_REQUEST,
        "Unable to access table unreadable-version.lake.unwritten: something is wrong with the"
            + " table. Please contact your data provider.",
        unwritten);

    // A log Kernel rejects is reported in Kernel's words, and the provider is to be contacted.
    Response gapped = version(lake + "gapped/version", bearer, null);
    assertError(
        400,
        ErrorCodes.BAD_REQUEST,
        "Invalid table found at the table: Cannot compute snapshot. Missing delta file version 0."
            + " Please contact your data provider.",
        gapped);
    Response noncontiguous = version(lake + "noncontiguous/version", bearer, null);
    assertError(
        400,
        ErrorCodes.BAD_REQUEST,
        "Invalid table found at the table: Missing delta files: versions are not contiguous:"
            + " ([0, 2]) Please contact your data provider.",
        noncontiguous);
    // Kernel only checks the in-commit timestamps of a log it must read commit by commit, so the
    // ask is a timestamped version. The table's directory has a space in its name.
    Response invalidLog = version(lake + "invalidlog/version", bearer, "2024-06-01T00:00:00Z");
    assertError(
        400,
        ErrorCodes.BAD_REQUEST,
        "Invalid table found at the table: This table has the feature inCommitTimestamp enabled"
            + " which requires the presence of the CommitInfo action in every commit. However, the"
            + " CommitInfo action is missing from commit version 0. Please contact your data"
            + " provider.",
        invalidLog);

    // A reader feature Kernel does not know is named, and the recipient is told whom to contact. A
    // table without a format is not a Delta table.
    Response future = version(lake + "future/version", bearer, null);
    assertError(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "Table features futurefeature are found in table. This server cannot read them. Please"
            + " contact your data provider.",
        future);
    Response raw = version(lake + "raw/version", bearer, null);
    assertError(400, ErrorCodes.INVALID_PARAMETER_VALUE, "the table is not a Delta table", raw);

    for (Response answer : List.of(unwritten, gapped, noncontiguous, invalidLog, future, raw)) {
      assertNoStoragePath(answer);
    }
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
