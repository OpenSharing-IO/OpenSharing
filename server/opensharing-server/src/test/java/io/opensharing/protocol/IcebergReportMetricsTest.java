package io.opensharing.protocol;

import static io.opensharing.protocol.IcebergConfigTest.ICEBERG;
import static io.opensharing.protocol.IcebergConfigTest.assertIcebergError;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import io.opensharing.TestServer.Response;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IcebergReportMetricsTest extends LocalIcebergTableSupport {

  private static final String SCAN_REPORT =
      """
      {"report-type": "scan-report", "table-name": "lake.trips", "snapshot-id": 1,
       "filter": true, "schema-id": 0, "projected-field-ids": [1],
       "projected-field-names": ["id"], "metrics": {}}
      """;

  @Test
  void acceptsAScanReportForAnIcebergTable() {
    // Share an Iceberg table and a Delta table under lake.
    createShare("iceberg-metrics");
    addObject("iceberg-metrics", "TABLE", "main.lake.trips", "lake.trips");
    addObject("iceberg-metrics", "TABLE", "main.sales.orders", "lake.orders");
    String bearer = createAndActivateRecipient("iceberg-metrics-partner");
    grant("iceberg-metrics", "iceberg-metrics-partner");
    String tablesPath = ICEBERG + "/v1/shares/iceberg-metrics/namespaces/lake/tables/";

    // A scan report for the Iceberg table is accepted with no content.
    Response reported = server.post(tablesPath + "TRIPS/metrics", bearer, SCAN_REPORT);
    assertEquals(204, reported.status());

    // One for the Delta table is a missing table.
    assertIcebergError(
        server.post(tablesPath + "orders/metrics", bearer, SCAN_REPORT),
        404,
        "NoSuchTableException");
    // So is one for a table the share doesn't have.
    assertIcebergError(
        server.post(tablesPath + "missing/metrics", bearer, SCAN_REPORT),
        404,
        "NoSuchTableException");

    // getConfig lists reportMetrics.
    JsonNode endpoints =
        server
            .get(ICEBERG + "/v1/config?warehouse=iceberg-metrics", bearer)
            .body()
            .path("endpoints");
    // Collect the advertised routes in their wire order.
    List<String> advertised = new ArrayList<>();
    // Compare the actual strings rather than just the presence of a single route.
    endpoints.forEach(endpoint -> advertised.add(endpoint.asText()));
    // The advertised routes match the read-only protocol, in order.
    assertEquals(
        List.of(
            "GET /v1/{prefix}/namespaces",
            "GET /v1/{prefix}/namespaces/{namespace}",
            "GET /v1/{prefix}/namespaces/{namespace}/tables",
            "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}",
            "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}/credentials",
            "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/metrics"),
        advertised);
  }
}
