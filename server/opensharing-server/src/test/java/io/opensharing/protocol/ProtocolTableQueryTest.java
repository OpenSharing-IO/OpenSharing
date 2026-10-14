package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Query Table through the local catalog and Kernel, on a local Delta log with no data files; file
 * URLs are signed only for cloud storage.
 */
class ProtocolTableQueryTest extends LocalDeltaTableSupport {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void queriesGrantedTable() {
    createShare("table-query");
    addObject("table-query", "TABLE", "main.ops.events", "ops.events");
    createShare("hidden-query");
    addObject("hidden-query", "TABLE", "main.sales.customers", "sales.customers");
    String bearer = createAndActivateRecipient("table-query-partner");
    grant("table-query", "table-query-partner");

    // The latest snapshot defaults to parquet NDJSON: protocol, then metaData, then files.
    String events = PROTOCOL + "/shares/TABLE-QUERY/schemas/OPS/tables/EVENTS/query";
    Response latest = query(events, bearer, "{}", null, null);
    assertEquals(200, latest.status());
    assertTrue(latest.headers().contentType().is(MediaType.parse("application/x-ndjson")));
    assertEquals("1", latest.headers().get("Delta-Table-Version"));
    assertEquals("responseformat=parquet", latest.headers().get("delta-sharing-capabilities"));
    assertNull(latest.headers().get("fileidhash"));
    List<JsonNode> lines = lines(latest);
    assertEquals(2, lines.size());
    assertEquals(1, lines.get(0).path("protocol").path("minReaderVersion").asInt());
    JsonNode metaData = lines.get(1).path("metaData");
    assertEquals("events", metaData.path("id").asText());
    assertEquals(location("events"), metaData.path("location").asText());
    assertTrue(metaData.path("version").isMissingNode());

    // The body is optional.
    assertEquals(200, query(events, bearer, null, null, null).status());

    // fileidhash is echoed in lowercase; unsupported values are INVALID_PARAMETER_VALUE.
    Response hashed = query(events, bearer, "{}", null, "PARQUET");
    assertEquals(200, hashed.status());
    assertEquals("parquet", hashed.headers().get("fileidhash"));
    assertInvalid(query(events, bearer, "{}", null, "md5"));

    // responseformat=delta wraps Kernel protocol and metadata as deltaProtocol/deltaMetadata.
    Response delta = query(events, bearer, "{}", "responseformat=delta", null);
    assertEquals(200, delta.status());
    assertEquals("responseformat=delta", delta.headers().get("delta-sharing-capabilities"));
    lines = lines(delta);
    JsonNode deltaProtocol = lines.get(0).path("protocol").path("deltaProtocol");
    assertEquals(2, deltaProtocol.path("minWriterVersion").asInt());
    assertEquals("events", lines.get(1).path("metaData").path("deltaMetadata").path("id").asText());
    assertInvalid(query(events, bearer, "{}", "responseformat=json", null));

    // A historical version or timestamp is echoed in metaData.version.
    Response atVersion = query(events, bearer, "{\"version\":0}", null, null);
    assertEquals(200, atVersion.status());
    assertEquals("0", atVersion.headers().get("Delta-Table-Version"));
    assertEquals(0, lines(atVersion).get(1).path("metaData").path("version").asLong());
    Response atTimestamp =
        query(events, bearer, "{\"timestamp\":\"2024-01-01T00:30:00Z\"}", null, null);
    assertEquals(200, atTimestamp.status());
    assertEquals("0", atTimestamp.headers().get("Delta-Table-Version"));

    assertInvalid(query(events, bearer, "{\"version\":9}", null, null));
    String both = "{\"version\":1,\"timestamp\":\"2024-01-01T00:30:00Z\"}";
    assertInvalid(query(events, bearer, both, null, null));
    assertInvalid(query(events, bearer, "{\"timestamp\":\"not-a-timestamp\"}", null, null));

    // A table in an ungranted share is 404, not 403.
    Response hidden =
        query(
            PROTOCOL + "/shares/hidden-query/schemas/sales/tables/customers/query",
            bearer,
            "{}",
            null,
            null);
    assertEquals(404, hidden.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, hidden.errorCode());
  }

  private static Response query(
      String path, String bearer, String body, String capabilities, String fileIdHash) {
    List<String> headers = new ArrayList<>();
    if (capabilities != null) {
      headers.addAll(List.of("delta-sharing-capabilities", capabilities));
    }
    if (fileIdHash != null) {
      headers.addAll(List.of("fileidhash", fileIdHash));
    }
    return server.call(HttpMethod.POST, path, bearer, body, headers.toArray(String[]::new));
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
    assertEquals(400, response.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, response.errorCode());
  }
}
