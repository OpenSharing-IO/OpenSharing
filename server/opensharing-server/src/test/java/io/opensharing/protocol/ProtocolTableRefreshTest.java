package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpMethod;
import io.opensharing.TestServer.Response;
import io.opensharing.util.ErrorCodes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Query Table refresh tokens and endStreamAction, on a local Delta log with no data files. */
class ProtocolTableRefreshTest extends LocalDeltaTableSupport {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void refreshesLatestSnapshotQueries() {
    createShare("table-refresh");
    addObject("table-refresh", "TABLE", "main.ops.events", "ops.events");
    addObject("table-refresh", "TABLE", "main.sales.orders", "sales.orders");
    String bearer = createAndActivateRecipient("table-refresh-partner");
    grant("table-refresh", "table-refresh-partner");
    String events = PROTOCOL + "/shares/table-refresh/schemas/ops/tables/events/query";

    // includeRefreshToken ends the response with an endStreamAction that holds the token.
    Response first = query(events, bearer, "{\"includeRefreshToken\":true}", null);
    assertEquals(200, first.status());
    assertEquals("responseformat=parquet", first.headers().get("delta-sharing-capabilities"));
    List<JsonNode> lines = lines(first);
    assertEquals(3, lines.size());
    String token = lines.get(2).path("endStreamAction").path("refreshToken").asText();
    assertFalse(token.isEmpty());

    // After a later commit, the token still reads the version it was issued for.
    appendCommit("events", 2);
    assertEquals("2", query(events, bearer, "{}", null).headers().get("Delta-Table-Version"));
    Response refreshed = query(events, bearer, refreshBody(token), null);
    assertEquals(200, refreshed.status());
    assertEquals("1", refreshed.headers().get("Delta-Table-Version"));
    lines = lines(refreshed);
    assertEquals(2, lines.size());
    assertTrue(lines.get(1).path("metaData").path("version").isMissingNode());

    // A token cannot be combined with a version, nor requested for one.
    String pinned = "{\"version\":0,\"refreshToken\":\"" + token + "\"}";
    assertInvalid(query(events, bearer, pinned, null));
    assertInvalid(query(events, bearer, "{\"version\":0,\"includeRefreshToken\":true}", null));
    // A token that does not decode, or was issued for another table, is rejected.
    assertInvalid(query(events, bearer, refreshBody("not-a-token"), null));
    String orders = PROTOCOL + "/shares/table-refresh/schemas/sales/tables/orders/query";
    assertInvalid(query(orders, bearer, refreshBody(token), null));

    // includeendstreamaction adds an endStreamAction without a token and is echoed back.
    Response withEndStream =
        query(events, bearer, "{}", "responseformat=delta;includeendstreamaction=true");
    assertEquals(200, withEndStream.status());
    assertEquals(
        "responseformat=delta;includeendstreamaction=true",
        withEndStream.headers().get("delta-sharing-capabilities"));
    JsonNode endStream = lines(withEndStream).get(2).path("endStreamAction");
    assertTrue(endStream.isObject());
    assertTrue(endStream.path("refreshToken").isMissingNode());
  }

  private static String refreshBody(String token) {
    return "{\"refreshToken\":\"" + token + "\"}";
  }

  private static Response query(String path, String bearer, String body, String capabilities) {
    return capabilities == null
        ? server.post(path, bearer, body)
        : server.call(
            HttpMethod.POST, path, bearer, body, "delta-sharing-capabilities", capabilities);
  }

  private static List<JsonNode> lines(Response response) {
    List<JsonNode> lines = new ArrayList<>();
    for (String line : response.text().split("\n")) {
      try {
        lines.add(JSON.readTree(line));
      } catch (JsonProcessingException e) {
        throw new AssertionError("not an NDJSON line: " + line, e);
      }
    }
    return lines;
  }

  private static void assertInvalid(Response response) {
    assertEquals(400, response.status(), response.text());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, response.errorCode());
  }
}
