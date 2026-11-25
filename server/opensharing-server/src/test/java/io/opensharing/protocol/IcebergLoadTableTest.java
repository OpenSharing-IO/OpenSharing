package io.opensharing.protocol;

import static io.opensharing.protocol.IcebergConfigTest.ICEBERG;
import static io.opensharing.protocol.IcebergConfigTest.assertAdvertised;
import static io.opensharing.protocol.IcebergConfigTest.assertIcebergError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.HttpMethod;
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
  void loadsTheCurrentMetadataWithCredentialsForTheTablesLocation() {
    // Load the table, asking for vended credentials.
    Response loaded = load(table("LAKE", "TRIPS"), "vended-credentials");
    assertEquals(200, loaded.status());
    JsonNode body = loaded.body();
    // It returns the catalog's metadata location and the metadata JSON there, unchanged.
    assertEquals(file(TRIPS_METADATA), body.path("metadata-location").asText());
    JsonNode metadata = body.path("metadata");
    assertEquals(TABLE_UUID, metadata.path("table-uuid").asText());
    assertEquals(2, metadata.path("format-version").asInt());
    JsonNode column = metadata.path("schemas").path(0).path("fields").path(0);
    assertEquals("id", column.path("name").asText());

    // The local catalog vends placeholder AWS credentials, scoped to the table's location.
    JsonNode credentials = body.path("storage-credentials");
    assertEquals(1, credentials.size());
    assertEquals(location("trips"), credentials.path(0).path("prefix").asText());
    JsonNode config = credentials.path(0).path("config");
    assertTrue(config.path("s3.access-key-id").asText().startsWith("ASIA"));
    assertTrue(config.has("s3.secret-access-key"));
    assertTrue(config.path("s3.session-token-expires-at-ms").asLong() > 0);
    // Clients that predate storage-credentials read the same properties from config.
    assertEquals(config, body.path("config"));
  }

  /** As the Iceberg REST spec defines, credentials are only vended to a client that asks. */
  @Test
  void vendsCredentialsOnlyWhenTheClientAsksForThem() {
    // Without vended-credentials in the header, only the metadata comes back.
    for (String delegation : new String[] {null, "remote-signing"}) {
      Response loaded = load(table("lake", "trips"), delegation);
      assertEquals(200, loaded.status());
      assertEquals(TABLE_UUID, loaded.body().path("metadata").path("table-uuid").asText());
      assertTrue(loaded.body().path("storage-credentials").isMissingNode());
      assertTrue(loaded.body().path("config").isMissingNode());
    }

    // The header is a comma-separated list, in any case.
    Response loaded = load(table("lake", "trips"), "remote-signing, Vended-Credentials");
    assertEquals(1, loaded.body().path("storage-credentials").size());
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
      Response failed = load(table("lake", broken), "vended-credentials");
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

  /** Loads {@code path}, with {@code delegation} as the access delegation header unless null. */
  private static Response load(String path, String delegation) {
    return delegation == null
        ? server.get(path, bearer)
        : server.call(
            HttpMethod.GET, path, bearer, null, "X-Iceberg-Access-Delegation", delegation);
  }

  private static String table(String namespace, String table) {
    return ICEBERG + "/v1/shares/iceberg-load/namespaces/" + namespace + "/tables/" + table;
  }
}
