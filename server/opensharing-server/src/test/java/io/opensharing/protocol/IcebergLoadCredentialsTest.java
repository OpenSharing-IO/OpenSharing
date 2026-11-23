package io.opensharing.protocol;

import static io.opensharing.protocol.IcebergConfigTest.ICEBERG;
import static io.opensharing.protocol.IcebergConfigTest.assertAdvertised;
import static io.opensharing.protocol.IcebergConfigTest.assertIcebergError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.opensharing.TestServer.Response;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class IcebergLoadCredentialsTest extends LocalIcebergTableSupport {

  private static String bearer;

  @BeforeAll
  static void shareTheTables() {
    createShare("iceberg-creds");
    addObject("iceberg-creds", "TABLE", "main.lake.trips", "lake.trips");
    addObject("iceberg-creds", "TABLE", "main.lake.vanishing", "lake.vanishing");
    addObject("iceberg-creds", "TABLE", "main.sales.orders", "lake.orders");
    bearer = createAndActivateRecipient("iceberg-creds-partner");
    grant("iceberg-creds", "iceberg-creds-partner");
  }

  @Test
  void vendsCredentialsForTheTablesLocation() {
    Response vended = server.get(credentials("iceberg-creds", "LAKE", "TRIPS"), bearer);
    assertEquals(200, vended.status());
    JsonNode credentials = vended.body().path("storage-credentials");
    assertEquals(1, credentials.size());
    assertEquals(location("trips"), credentials.path(0).path("prefix").asText());
    JsonNode config = credentials.path(0).path("config");
    assertTrue(config.path("s3.access-key-id").asText().startsWith("ASIA"));
    assertTrue(config.has("s3.session-token"));
    assertTrue(config.path("s3.session-token-expires-at-ms").asLong() > 0);
    assertTrue(vended.body().path("metadata").isMissingNode());
  }

  @Test
  void vendsCredentialsForTheCatalogLocationAfterATableMoves() {
    // Prepare a share for the table.
    createShare("iceberg-creds-moved");
    // Record its original table.
    addObject("iceberg-creds-moved", "TABLE", "main.lake.trips", "lake.trips");
    // Create a recipient for the share.
    String partner = createAndActivateRecipient("iceberg-creds-moved-partner");
    // Let the recipient read the share.
    grant("iceberg-creds-moved", "iceberg-creds-moved-partner");
    // Simulate the catalog moving the table after it was shared.
    moveInCatalog("iceberg-creds-moved", "main.lake.trips", location("old-trips"));

    // Request credentials after the move.
    Response vended = server.get(credentials("iceberg-creds-moved", "lake", "trips"), partner);
    // The stale share location does not make the request fail.
    assertEquals(200, vended.status());
    // The catalog's current location is returned instead.
    assertEquals(
        location("trips"),
        vended.body().path("storage-credentials").path(0).path("prefix").asText());

    // A missing recorded location also permits catalog credentials.
    moveInCatalog("iceberg-creds-moved", "main.lake.trips", null);
    // Request credentials with no recorded location.
    Response withoutRecordedLocation =
        server.get(credentials("iceberg-creds-moved", "lake", "trips"), partner);
    // The request still succeeds.
    assertEquals(200, withoutRecordedLocation.status());
    // The catalog still supplies its current location.
    assertEquals(
        location("trips"),
        withoutRecordedLocation.body().path("storage-credentials").path(0).path("prefix").asText());
  }

  /** The table is not resolved again, so credentials don't depend on reading its metadata. */
  @Test
  void vendsCredentialsWithoutResolvingTheTable() throws IOException {
    Files.delete(tables.resolve(VANISHING_METADATA));

    Response vended = server.get(credentials("iceberg-creds", "lake", "vanishing"), bearer);
    assertEquals(200, vended.status());
    assertEquals(
        location("vanishing"),
        vended.body().path("storage-credentials").path(0).path("prefix").asText());
    // loadTable resolves it, and the catalog can no longer read its metadata.
    String loadTable = ICEBERG + "/v1/shares/iceberg-creds/namespaces/lake/tables/vanishing";
    assertIcebergError(server.get(loadTable, bearer), 502, "ServiceFailureException");
  }

  /** The catalog refuses credentials for a table that is gone, or that another took over. */
  @Test
  void reportsTablesTheCatalogNoLongerHasAsNoSuchTable() {
    createShare("iceberg-creds-gone");
    addObject("iceberg-creds-gone", "TABLE", "main.lake.trips", "lake.replaced");
    addObject("iceberg-creds-gone", "TABLE", "main.lake.astray", "lake.dropped");
    String partner = createAndActivateRecipient("iceberg-creds-gone-partner");
    grant("iceberg-creds-gone", "iceberg-creds-gone-partner");
    replaceInCatalog("iceberg-creds-gone", "main.lake.trips");
    dropFromCatalog("iceberg-creds-gone", "main.lake.astray");

    Response replaced = server.get(credentials("iceberg-creds-gone", "lake", "replaced"), partner);
    assertIcebergError(replaced, 404, "NoSuchTableException");
    assertEquals(
        "table 'lake.replaced' does not exist in share 'iceberg-creds-gone'",
        replaced.body().path("error").path("message").asText());
    assertIcebergError(
        server.get(credentials("iceberg-creds-gone", "lake", "dropped"), partner),
        404,
        "NoSuchTableException");
  }

  @Test
  void reportsTablesItCannotLoadAsNoSuchTable() {
    assertIcebergError(
        server.get(credentials("iceberg-creds", "lake", "missing"), bearer),
        404,
        "NoSuchTableException");
    assertIcebergError(
        server.get(credentials("iceberg-creds", "lake", "orders"), bearer),
        404,
        "NoSuchTableException");
    assertIcebergError(
        server.get(credentials("iceberg-creds", "missing", "trips"), bearer),
        404,
        "NoSuchTableException");
  }

  @Test
  void configListsLoadCredentials() {
    JsonNode endpoints =
        server.get(ICEBERG + "/v1/config?warehouse=iceberg-creds", bearer).body().path("endpoints");
    assertAdvertised(
        endpoints, "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}/credentials");
  }

  private static String credentials(String share, String namespace, String table) {
    return ICEBERG
        + "/v1/shares/"
        + share
        + "/namespaces/"
        + namespace
        + "/tables/"
        + table
        + "/credentials";
  }
}
