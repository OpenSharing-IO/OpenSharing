package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linecorp.armeria.common.HttpMethod;
import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Query Table from a startingVersion, on a local Delta log with no data files. */
class ProtocolTableStartingVersionTest extends LocalDeltaTableSupport {

  @Test
  void queriesGrantedTableChangesFromStartingVersion() {
    createShare("table-changes");
    addObject("table-changes", "TABLE", "main.ops.events", "ops.events");
    String bearer = createAndActivateRecipient("table-changes-partner");
    grant("table-changes", "table-changes-partner");
    String query = PROTOCOL + "/shares/table-changes/schemas/ops/tables/events/query";

    // Delta-Table-Version is the startingVersion, and the protocol and metaData lead.
    Response changes =
        server.call(
            HttpMethod.POST,
            query,
            bearer,
            "{\"startingVersion\":0,\"endingVersion\":1,\"includeHistoricalProtocol\":true}",
            "delta-sharing-capabilities",
            "includeendstreamaction=true");
    assertEquals(200, changes.status(), changes.text());
    assertEquals("0", changes.headers().get("Delta-Table-Version"));
    assertEquals(
        "responseformat=parquet;includeendstreamaction=true",
        changes.headers().get("delta-sharing-capabilities"));
    String[] lines = changes.text().split("\n");
    assertTrue(lines[0].startsWith("{\"protocol\""), changes.text());
    assertTrue(lines[1].startsWith("{\"metaData\""), changes.text());
    assertTrue(lines[lines.length - 1].startsWith("{\"endStreamAction\""), changes.text());
    // Without endingVersion the range runs through the latest version.
    Response latest = server.post(query, bearer, "{\"startingVersion\":1}");
    assertEquals(200, latest.status(), latest.text());
    assertEquals("1", latest.headers().get("Delta-Table-Version"));

    for (String invalid :
        List.of(
            "{\"endingVersion\":1}",
            "{\"startingVersion\":-1}",
            "{\"startingVersion\":1,\"endingVersion\":0}",
            "{\"startingVersion\":2}",
            "{\"startingVersion\":0,\"endingVersion\":2}",
            "{\"startingVersion\":0,\"version\":0}",
            "{\"startingVersion\":0,\"timestamp\":\"2024-01-01T00:00:00Z\"}",
            "{\"startingVersion\":0,\"includeRefreshToken\":true}",
            "{\"startingVersion\":0,\"refreshToken\":\"token\"}")) {
      Response response = server.post(query, bearer, invalid);
      assertEquals(400, response.status(), invalid);
      assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, response.errorCode(), invalid);
    }
  }
}
