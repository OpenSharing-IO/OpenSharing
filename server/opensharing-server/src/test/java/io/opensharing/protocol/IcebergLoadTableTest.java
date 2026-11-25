package io.opensharing.protocol;

import static io.opensharing.protocol.IcebergConfigTest.ICEBERG;
import static io.opensharing.protocol.IcebergConfigTest.assertAdvertised;
import static io.opensharing.protocol.IcebergConfigTest.assertIcebergError;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import io.opensharing.TestServer.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class IcebergLoadTableTest extends LocalIcebergTableSupport {

  private static String bearer;

  @BeforeAll
  static void shareTheTables() {
    createShare("iceberg-load");
    addObject("iceberg-load", "TABLE", "main.lake.trips", "lake.trips");
    addObject("iceberg-load", "TABLE", "main.lake.astray", "lake.astray");
    addObject("iceberg-load", "TABLE", "main.lake.unpointed", "lake.unpointed");
    addObject("iceberg-load", "TABLE", "main.sales.orders", "lake.orders");
    bearer = createAndActivateRecipient("iceberg-load-partner");
    grant("iceberg-load", "iceberg-load-partner");
  }

  @Test
  void loadsTheCurrentMetadata() {
    // Load the table.
    Response loaded = server.get(table("LAKE", "TRIPS"), bearer);
    assertEquals(200, loaded.status());
    JsonNode body = loaded.body();
    // It returns the catalog's metadata location and the metadata JSON there, unchanged.
    assertEquals(file(TRIPS_METADATA), body.path("metadata-location").asText());
    JsonNode metadata = body.path("metadata");
    assertEquals(TABLE_UUID, metadata.path("table-uuid").asText());
    assertEquals(2, metadata.path("format-version").asInt());
    JsonNode column = metadata.path("schemas").path(0).path("fields").path(0);
    assertEquals("id", column.path("name").asText());
  }

  @Test
  void reportsTablesItCannotLoadAsNoSuchTable() {
    // A table the share doesn't have is a missing table.
    assertIcebergError(server.get(table("lake", "missing"), bearer), 404, "NoSuchTableException");
    // So is a Delta table, which the Iceberg catalog can't load.
    Response delta = server.get(table("lake", "orders"), bearer);
    assertIcebergError(delta, 404, "NoSuchTableException");
    assertEquals(
        "table 'lake.orders' is not an Iceberg table",
        delta.body().path("error").path("message").asText());
    // A schema the share doesn't have is a missing table too, as the Iceberg spec has it.
    assertIcebergError(server.get(table("missing", "trips"), bearer), 404, "NoSuchTableException");
  }

  /** What is wrong with the catalog's pointer stays in the log, as for any catalog failure. */
  @Test
  void reportsBadMetadataPointersAsServiceFailures() {
    // Metadata outside the table's location, or no metadata location, fails generically.
    for (String broken : new String[] {"astray", "unpointed"}) {
      Response failed = server.get(table("lake", broken), bearer);
      assertIcebergError(failed, 502, "ServiceFailureException");
      assertEquals(
          "the catalog failed to serve the request",
          failed.body().path("error").path("message").asText());
    }
  }

  @Test
  void configListsLoadTable() {
    // getConfig lists loadTable.
    JsonNode endpoints =
        server.get(ICEBERG + "/v1/config?warehouse=iceberg-load", bearer).body().path("endpoints");
    assertAdvertised(endpoints, "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}");
  }

  private static String table(String namespace, String table) {
    return ICEBERG + "/v1/shares/iceberg-load/namespaces/" + namespace + "/tables/" + table;
  }
}
