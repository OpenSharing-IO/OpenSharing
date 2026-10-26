package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    addObject("table-metadata", "SCHEMA", "main.hr", "hr");
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

    // Unknown responseformat and capability keys are INVALID_PARAMETER_VALUE.
    assertInvalid(metadata(orders, bearer, "responseformat=json"));
    assertInvalid(metadata(orders, bearer, "unknown=true"));

    // A historical version or timestamp is echoed in metaData.version.
    Response atVersion = metadata(orders + "?version=1", bearer, null);
    assertEquals(200, atVersion.status());
    assertEquals("1", atVersion.headers().get("Delta-Table-Version"));
    assertEquals(1, lines(atVersion).get(1).path("metaData").path("version").asLong());
    String salaries = PROTOCOL + "/shares/table-metadata/schemas/hr/tables/SALARIES/metadata";
    Response atTimestamp = metadata(salaries + "?timestamp=2024-01-01T01:30:00Z", bearer, null);
    assertEquals(200, atTimestamp.status());
    assertEquals("1", atTimestamp.headers().get("Delta-Table-Version"));

    assertInvalid(metadata(orders + "?version=9", bearer, null));
    assertInvalid(metadata(orders + "?version=1&timestamp=2024-01-01T01:30:00Z", bearer, null));
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
  void reportsADeltaTableWithoutALogAsACatalogError() {
    createShare("unwritten-metadata");
    addObject("unwritten-metadata", "TABLE", "main.lake.unwritten", "lake.unwritten");
    String bearer = createAndActivateRecipient("unwritten-metadata-partner");
    grant("unwritten-metadata", "unwritten-metadata-partner");

    Response unwritten =
        metadata(
            PROTOCOL + "/shares/unwritten-metadata/schemas/lake/tables/unwritten/metadata",
            bearer,
            null);
    assertEquals(502, unwritten.status());
    assertEquals(ErrorCodes.CATALOG_ERROR, unwritten.errorCode());
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
