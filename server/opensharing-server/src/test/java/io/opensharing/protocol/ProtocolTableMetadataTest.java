package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/** Query Table Metadata through the local catalog and Kernel, on local Delta logs. */
class ProtocolTableMetadataTest extends LocalDeltaTableSupport {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void queriesGrantedTableMetadata() {
    createShare("table-metadata");
    addObject("table-metadata", "TABLE", "main.sales.orders", "sales.orders");
    addObject("table-metadata", "TABLE", "main.hr.salaries", "hr.salaries");
    createShare("hidden-metadata");
    addObject("hidden-metadata", "TABLE", "main.sales.customers", "sales.customers");
    String bearer = createAndActivateRecipient("table-metadata-partner");
    grant("table-metadata", "table-metadata-partner");

    // Latest metadata defaults to parquet NDJSON: protocol, then metaData.
    String orders = PROTOCOL + "/shares/TABLE-METADATA/schemas/SALES/tables/ORDERS/metadata";
    Response latest = metadata(orders, bearer, null);
    assertEquals(200, latest.status());
    assertTrue(latest.headers().contentType().is(MediaType.parse("application/x-ndjson")));
    assertEquals("2", latest.headers().get("Delta-Table-Version"));
    assertEquals("responseformat=parquet", latest.headers().get("delta-sharing-capabilities"));
    List<JsonNode> lines = lines(latest);
    assertEquals(2, lines.size());
    assertEquals(1, lines.get(0).path("protocol").path("minReaderVersion").asInt());
    JsonNode metaData = lines.get(1).path("metaData");
    assertEquals("orders", metaData.path("id").asText());
    assertEquals("parquet", metaData.path("format").path("provider").asText());
    assertTrue(metaData.path("schemaString").asText().contains("\"name\":\"id\""));
    assertEquals(location("orders"), metaData.path("location").asText());
    assertEquals("url", metaData.path("accessModes").path(0).asText());
    assertTrue(metaData.path("version").isMissingNode());
    assertTrue(metaData.path("deltaMetadata").isMissingNode());
    assertTrue(metaData.path("configuration").isObject());
    assertEquals(0, metaData.path("configuration").size());

    // responseformat=delta wraps Kernel protocol and metadata as deltaProtocol/deltaMetadata.
    Response delta = metadata(orders, bearer, "responseformat=delta");
    assertEquals(200, delta.status());
    assertEquals("responseformat=delta", delta.headers().get("delta-sharing-capabilities"));
    lines = lines(delta);
    JsonNode deltaProtocol = lines.get(0).path("protocol").path("deltaProtocol");
    assertEquals(1, deltaProtocol.path("minReaderVersion").asInt());
    assertEquals(2, deltaProtocol.path("minWriterVersion").asInt());
    assertEquals("orders", lines.get(1).path("metaData").path("deltaMetadata").path("id").asText());
    Response both = metadata(orders, bearer, "responseformat=delta,parquet");
    assertEquals("responseformat=parquet", both.headers().get("delta-sharing-capabilities"));

    // An unknown responseformat is INVALID_PARAMETER_VALUE; unknown capability keys are ignored.
    assertInvalid(metadata(orders, bearer, "responseformat=json"));
    Response unknown = metadata(orders, bearer, "unknown=true;responseformat=delta");
    assertEquals(200, unknown.status());
    assertEquals("responseformat=delta", unknown.headers().get("delta-sharing-capabilities"));

    // A historical version or timestamp is echoed in metaData.version.
    Response atVersion = metadata(orders + "?version=1", bearer, null);
    assertEquals(200, atVersion.status());
    assertEquals("1", atVersion.headers().get("Delta-Table-Version"));
    assertEquals(1, lines(atVersion).get(1).path("metaData").path("version").asLong());
    String salaries = PROTOCOL + "/shares/table-metadata/schemas/hr/tables/SALARIES/metadata";
    Response atTimestamp = metadata(salaries + "?timestamp=2024-01-01T01:30:00Z", bearer, null);
    assertEquals(200, atTimestamp.status());
    assertEquals("1", atTimestamp.headers().get("Delta-Table-Version"));

    assertError(
        400,
        ErrorCodes.BAD_REQUEST,
        "Cannot load table version 9 as it does not exist. The latest available version is 2.",
        metadata(orders + "?version=9", bearer, null));
    assertInvalid(metadata(orders + "?timestamp=not-a-timestamp", bearer, null));

    // A table in an ungranted share is 404, not 403.
    Response hidden =
        metadata(
            PROTOCOL + "/shares/hidden-metadata/schemas/sales/tables/customers/metadata",
            bearer,
            null);
    assertEquals(404, hidden.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, hidden.errorCode());
  }

  @Test
  void answersVersionAndTimestampMistakes() {
    createShare("mistakes-metadata");
    addObject("mistakes-metadata", "TABLE", "main.sales.orders", "sales.orders");
    String bearer = createAndActivateRecipient("mistakes-metadata-partner");
    grant("mistakes-metadata", "mistakes-metadata-partner");
    String orders = PROTOCOL + "/shares/mistakes-metadata/schemas/sales/tables/orders/metadata";

    assertError(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "Please only provide one of: version,timestamp",
        metadata(orders + "?version=1&timestamp=2024-01-01T01:30:00Z", bearer, null));
    assertError(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "version cannot be negative.",
        metadata(orders + "?version=-1", bearer, null));
    assertError(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "version is not a valid number.",
        metadata(orders + "?version=latest", bearer, null));
    // A timestamp before the first commit is Kernel's report, without the storage path.
    Response early = metadata(orders + "?timestamp=2000-01-01T00:00:00Z", bearer, null);
    assertError(
        400,
        ErrorCodes.BAD_REQUEST,
        "The provided timestamp 946684800000 ms (2000-01-01T00:00:00Z) is before the earliest"
            + " available version 0. Please use a timestamp greater than or equal to"
            + " 1704067200000 ms (2024-01-01T00:00:00Z).",
        early);
    assertNoStoragePath(early);
  }

  private static Response metadata(String path, String bearer, String capabilities) {
    return capabilities == null
        ? server.get(path, bearer)
        : server.call(
            HttpMethod.GET, path, bearer, null, "delta-sharing-capabilities", capabilities);
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
