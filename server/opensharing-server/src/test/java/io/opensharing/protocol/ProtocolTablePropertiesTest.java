package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.linecorp.armeria.common.HttpMethod;
import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import org.junit.jupiter.api.Test;

/**
 * A table whose Delta properties enable a reader feature the client did not advertise is not
 * served: column mapping by name, and deletion vectors, are only servable to a client that
 * advertises them. The answer names the properties, and the version they were found in.
 */
class ProtocolTablePropertiesTest extends LocalDeltaTableSupport {

  private static final String REMEDY =
      " Query with responseFormat=delta and advertise the features in delta-sharing-capabilities,"
          + " or ask your data provider to disable them.";
  private static final String DELTA = "responseformat=delta";

  @Test
  void rejectsUnadvertisedColumnMappingOnEveryAnswer() {
    String cm = shared("cm-table", "cm");
    String bearer = recipient("cm-table");
    String message =
        "Table features delta.columnMapping.mode are found in table version: 0." + REMEDY;

    for (Response answer :
        new Response[] {
          server.get(cm + "/metadata", bearer),
          query(cm, bearer, null),
          query(cm, bearer, DELTA),
          query(cm, bearer, DELTA + ";readerfeatures=deletionvectors")
        }) {
      assertError(400, ErrorCodes.INVALID_PARAMETER_VALUE, message, answer);
      assertNoStoragePath(answer);
    }

    // A client that advertises columnMapping, in any case, is served.
    for (String advertised : new String[] {"columnmapping", "columnMapping", "COLUMNMAPPING"}) {
      Response served = query(cm, bearer, DELTA + ";readerfeatures=" + advertised);
      assertEquals(200, served.status(), served.text());
      assertEquals("0", served.headers().get("Delta-Table-Version"));
    }
  }

  @Test
  void rejectsUnadvertisedDeletionVectors() {
    String dv = shared("dv-table", "dv");
    String bearer = recipient("dv-table");
    String message =
        "Table features delta.enableDeletionVectors are found in table version: 0." + REMEDY;

    assertError(
        400, ErrorCodes.INVALID_PARAMETER_VALUE, message, server.get(dv + "/metadata", bearer));
    assertError(400, ErrorCodes.INVALID_PARAMETER_VALUE, message, query(dv, bearer, DELTA));
    assertError(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        message,
        query(dv, bearer, DELTA + ";readerfeatures=columnmapping"));

    Response served = query(dv, bearer, DELTA + ";readerfeatures=deletionvectors");
    assertEquals(200, served.status(), served.text());
  }

  @Test
  void servesATableWhosePropertiesAreOffOrUnrelated() {
    String propsoff = shared("props-off", "propsoff");
    String bearer = recipient("props-off");

    // Deletion vectors stay in the protocol after they are turned off, which does not block a
    // client that did not advertise them.
    assertEquals(200, server.get(propsoff + "/metadata", bearer).status());
    Response served = query(propsoff, bearer, DELTA);
    assertEquals(200, served.status(), served.text());
  }

  @Test
  void checksTheVersionOfASnapshotItServes() {
    String cmlater = shared("cm-later", "cmlater");
    String bearer = recipient("cm-later");

    // Version 0 has no column mapping, and version 1 does.
    assertEquals(200, server.get(cmlater + "/metadata?version=0", bearer).status());
    assertEquals(200, query(cmlater, bearer, "{\"version\":0}", DELTA).status());
    String atVersion1 =
        "Table features delta.columnMapping.mode are found in table version: 1." + REMEDY;
    assertError(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        atVersion1,
        server.get(cmlater + "/metadata", bearer));
    assertError(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        atVersion1,
        server.get(cmlater + "/metadata?version=1", bearer));
    assertError(
        400, ErrorCodes.INVALID_PARAMETER_VALUE, atVersion1, query(cmlater, bearer, "{}", DELTA));
  }

  @Test
  void checksTheMetadataOfEveryCommitOfAChangeFeed() {
    String cmlater = shared("cm-changes", "cmlater");
    String bearer = recipient("cm-changes");
    String atVersion1 =
        "Table features delta.columnMapping.mode are found in table version: 1." + REMEDY;

    // The range starts before column mapping is enabled, so its header is plain and only the
    // metadata that version 1 commits carries it. The range is read whole before the response
    // begins, so the error is the response's status.
    assertError(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        atVersion1,
        server.call(
            HttpMethod.GET,
            cmlater + "/changes?startingVersion=0&includeHistoricalMetadata=true",
            bearer,
            null,
            "delta-sharing-capabilities",
            DELTA));

    // A range that starts at version 1 has column mapping in its header.
    assertError(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        atVersion1,
        server.call(
            HttpMethod.GET,
            cmlater + "/changes?startingVersion=1",
            bearer,
            null,
            "delta-sharing-capabilities",
            DELTA));
    Response advertised =
        server.call(
            HttpMethod.GET,
            cmlater + "/changes?startingVersion=0",
            bearer,
            null,
            "delta-sharing-capabilities",
            DELTA + ";readerfeatures=columnmapping");
    assertEquals(200, advertised.status(), advertised.text());
  }

  /** Shares table {@code lake.<table>} in share {@code share}, and returns its endpoint. */
  private String shared(String share, String table) {
    createShare(share);
    addObject(share, "TABLE", "main.lake." + table, "lake." + table);
    return PROTOCOL + "/shares/" + share + "/schemas/lake/tables/" + table;
  }

  private String recipient(String share) {
    String bearer = createAndActivateRecipient(share + "-partner");
    grant(share, share + "-partner");
    return bearer;
  }

  private static Response query(String table, String bearer, String capabilities) {
    return query(table, bearer, "{}", capabilities);
  }

  private static Response query(String table, String bearer, String body, String capabilities) {
    return capabilities == null
        ? server.post(table + "/query", bearer, body)
        : server.call(
            HttpMethod.POST,
            table + "/query",
            bearer,
            body,
            "delta-sharing-capabilities",
            capabilities);
  }
}
