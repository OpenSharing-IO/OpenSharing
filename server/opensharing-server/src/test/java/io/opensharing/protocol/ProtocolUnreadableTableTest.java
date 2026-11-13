package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every table endpoint answers a source table that cannot be read the same way: the table's own
 * failures get the provider-contact answer in the share's names, and never the storage path.
 */
class ProtocolUnreadableTableTest extends LocalDeltaTableSupport {

  private static final String UNREADABLE =
      "Unable to access table unreadable-unwritten.lake.unwritten: something is wrong with the"
          + " table. Please contact your data provider.";
  private static final String INVALID_LOG =
      "Invalid table found at the table: This table has the feature inCommitTimestamp enabled"
          + " which requires the presence of the CommitInfo action in every commit. However, the"
          + " CommitInfo action is missing from commit version 0. Please contact your data"
          + " provider.";
  private static final String FUTURE =
      "Table features futurefeature are found in table. This server cannot read them. Please"
          + " contact your data provider.";

  @Test
  void answersEveryEndpointForATableWithoutALog() {
    String lake = shareWith("unwritten");
    String bearer = recipient("unwritten");

    for (Response answer :
        List.of(
            server.get(lake + "unwritten/version?startingTimestamp=2024-01-01T00:00:00Z", bearer),
            server.get(lake + "unwritten/metadata", bearer),
            server.post(lake + "unwritten/query", bearer, "{}"),
            server.post(lake + "unwritten/query", bearer, "{\"startingVersion\":0}"),
            server.get(lake + "unwritten/changes?startingVersion=0", bearer))) {
      assertError(400, ErrorCodes.BAD_REQUEST, UNREADABLE, answer);
      assertNoStoragePath(answer);
    }
  }

  @Test
  void namesTheFeaturesOfATableKernelCannotReadOnEveryEndpoint() {
    String lake = shareWith("future");
    String bearer = recipient("future");

    for (Response answer :
        List.of(
            server.get(lake + "future/metadata", bearer),
            server.post(lake + "future/query", bearer, "{}"),
            server.get(lake + "future/changes?startingVersion=0", bearer))) {
      assertError(400, ErrorCodes.INVALID_PARAMETER_VALUE, FUTURE, answer);
      assertNoStoragePath(answer);
    }
  }

  @Test
  void namesTheVersionOfAnEarlierSnapshotKernelCannotRead() {
    String lake = shareWith("futuresince");
    String bearer = recipient("futuresince");
    String atVersion0 =
        "Table features futurefeature are found in table version: 0. This server cannot read"
            + " them. Please contact your data provider.";

    // The latest version is readable; version 0 is not.
    assertEquals(200, server.get(lake + "futuresince/metadata", bearer).status());
    for (Response answer :
        List.of(
            server.get(lake + "futuresince/metadata?version=0", bearer),
            server.post(lake + "futuresince/query", bearer, "{\"version\":0}"),
            server.get(lake + "futuresince/changes?startingVersion=0", bearer))) {
      assertError(400, ErrorCodes.INVALID_PARAMETER_VALUE, atVersion0, answer);
      assertNoStoragePath(answer);
    }
  }

  @Test
  void rejectsAChangeRangeThatStopsRecordingBeforeAnyLineIsWritten() {
    String lake = shareWith("cdfmid");
    String bearer = recipient("cdfmid");
    String changes = lake + "cdfmid/changes";
    String version2 =
        "Unable to access change data feed for unreadable-cdfmid.lake.cdfmid. CDF is not enabled"
            + " on the original delta table for version 2. Please contact your data provider.";

    // A commit that changes nothing is part of a range, and gives no file.
    Response quiet = server.get(changes + "?startingVersion=0&endingVersion=1", bearer);
    assertEquals(200, quiet.status(), quiet.text());
    assertEquals(2, quiet.text().strip().split("\n").length, quiet.text());
    // The range is read whole before the response begins, so a version that stops recording
    // answers 403 however the range is bounded, and not a reset stream.
    for (String bounds :
        List.of(
            "startingVersion=0",
            "startingVersion=0&endingVersion=2",
            "startingTimestamp=2000-01-01T00:00:00Z")) {
      assertError(
          403,
          ErrorCodes.PERMISSION_DENIED,
          version2,
          server.get(changes + "?" + bounds, bearer));
    }
    // A timestamp after the latest commit is rejected too.
    Response late =
        server.get(changes + "?startingVersion=0&endingTimestamp=2100-01-01T00:00:00Z", bearer);
    assertEquals(400, late.status(), late.text());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, late.errorCode(), late.text());
  }

  @Test
  void reportsAnInvalidLogInKernelsWordsWithoutThePathInEitherSpelling() {
    String lake = shareWith("invalidlog");
    String bearer = recipient("invalidlog");

    // Kernel reads the in-commit timestamps of a log only when asked for a version by timestamp.
    for (Response answer :
        List.of(
            server.post(
                lake + "invalidlog/query", bearer, "{\"timestamp\":\"2024-06-01T00:00:00Z\"}"),
            server.post(lake + "invalidlog/query", bearer, "{\"version\":0}"),
            server.get(lake + "invalidlog/metadata?timestamp=2024-06-01T00:00:00Z", bearer),
            server.get(lake + "invalidlog/version?startingTimestamp=2024-06-01T00:00:00Z", bearer),
            server.get(lake + "invalidlog/changes?startingTimestamp=2024-06-01T00:00:00Z", bearer),
            server.get(
                lake + "invalidlog/changes?startingVersion=0&endingTimestamp=2024-06-01T00:00:00Z",
                bearer))) {
      assertError(400, ErrorCodes.BAD_REQUEST, INVALID_LOG, answer);
      assertNoStoragePath(answer);
    }
  }

  /** A share named for {@code table}, which holds only it, as {@code lake.<table>}. */
  private String shareWith(String table) {
    String share = "unreadable-" + table;
    createShare(share);
    addObject(share, "TABLE", "main.lake." + table, "lake." + table);
    return PROTOCOL + "/shares/" + share + "/schemas/lake/tables/";
  }

  /** The bearer token of a new recipient granted the share {@link #shareWith} made. */
  private String recipient(String table) {
    String bearer = createAndActivateRecipient("unreadable-" + table + "-partner");
    grant("unreadable-" + table, "unreadable-" + table + "-partner");
    return bearer;
  }
}
