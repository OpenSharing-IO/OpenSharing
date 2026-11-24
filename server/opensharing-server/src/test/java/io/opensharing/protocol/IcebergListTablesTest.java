package io.opensharing.protocol;

import static io.opensharing.protocol.IcebergConfigTest.ICEBERG;
import static io.opensharing.protocol.IcebergConfigTest.assertIcebergError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.opensharing.TestServer.Response;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class IcebergListTablesTest extends LocalIcebergTableSupport {

  /** The Iceberg catalog can only load Iceberg tables, so it lists no others. */
  @Test
  void listsOnlyTheNamespacesIcebergTables() {
    // Share a Delta and an Iceberg table under mixed, and a Delta table under delta.
    createShare("iceberg-tables");
    addObject("iceberg-tables", "TABLE", "main.sales.orders", "mixed.orders");
    addObject("iceberg-tables", "TABLE", "main.lake.trips", "mixed.trips");
    addObject("iceberg-tables", "TABLE", "main.sales.customers", "delta.customers");
    String bearer = createAndActivateRecipient("iceberg-tables-partner");
    grant("iceberg-tables", "iceberg-tables-partner");
    String namespaces = ICEBERG + "/v1/shares/iceberg-tables/namespaces/";

    // Under mixed, only the Iceberg table is listed.
    Response mixed = server.get(namespaces + "MIXED/tables", bearer);
    assertEquals(200, mixed.status());
    JsonNode identifiers = mixed.body().path("identifiers");
    assertEquals(1, identifiers.size());
    assertEquals("mixed", identifiers.path(0).path("namespace").path(0).asText());
    assertEquals("trips", identifiers.path(0).path("name").asText());
    assertTrue(mixed.body().path("next-page-token").isNull());

    // A namespace of Delta tables only is empty, not missing.
    Response delta = server.get(namespaces + "delta/tables", bearer);
    assertEquals(200, delta.status());
    assertEquals(0, delta.body().path("identifiers").size());

    // A schema the share doesn't have is a missing namespace.
    assertIcebergError(
        server.get(namespaces + "missing/tables", bearer), 404, "NoSuchNamespaceException");
  }

  @Test
  void pagesThroughIcebergTables() {
    // Share two Iceberg tables under lake.
    createShare("iceberg-tables-paged");
    addObject("iceberg-tables-paged", "TABLE", "main.lake.trips", "lake.a");
    addObject("iceberg-tables-paged", "TABLE", "main.lake.stations", "lake.b");
    String bearer = createAndActivateRecipient("iceberg-tables-paged-partner");
    grant("iceberg-tables-paged", "iceberg-tables-paged-partner");
    String tables = ICEBERG + "/v1/shares/iceberg-tables-paged/namespaces/lake/tables";

    // With pageSize=1 and an empty pageToken, the first page has a and a token for the next.
    Response first = server.get(tables + "?pageSize=1&pageToken=", bearer);
    assertEquals("a", first.body().path("identifiers").path(0).path("name").asText());
    String token = first.body().path("next-page-token").asText();
    // The token's page has b, and no further token.
    Response second = server.get(tables + "?pageSize=1&pageToken=" + token, bearer);
    assertEquals(1, second.body().path("identifiers").size());
    assertEquals("b", second.body().path("identifiers").path(0).path("name").asText());
    assertTrue(second.body().path("next-page-token").isNull());
  }

  /** Without a pageToken a request gets every table, whatever its pageSize. */
  @Test
  void returnsEveryTableWhenNoPageTokenIsGiven() {
    // Share two Iceberg tables under lake, so a pageSize of 1 would split them.
    createShare("iceberg-tables-all");
    addObject("iceberg-tables-all", "TABLE", "main.lake.trips", "lake.a");
    addObject("iceberg-tables-all", "TABLE", "main.lake.stations", "lake.b");
    // Activate a recipient for the share.
    String bearer = createAndActivateRecipient("iceberg-tables-all-partner");
    // Grant access to the share.
    grant("iceberg-tables-all", "iceberg-tables-all-partner");

    // Ask for a page of one table without a pageToken.
    Response response =
        server.get(
            ICEBERG + "/v1/shares/iceberg-tables-all/namespaces/lake/tables?pageSize=1", bearer);

    // Both tables come back at once.
    assertEquals(200, response.status());
    assertEquals(2, response.body().path("identifiers").size());
    // The response is the last, so its next-page-token is null rather than missing.
    assertTrue(response.body().path("next-page-token").isNull());
  }

  @Test
  void rejectsNonPositivePageSizes() {
    // Share an Iceberg table so the namespace exists.
    createShare("iceberg-tables-page-size");
    // Include its table under lake.
    addObject("iceberg-tables-page-size", "TABLE", "main.lake.trips", "lake.trips");
    // Activate a recipient for the share.
    String bearer = createAndActivateRecipient("iceberg-tables-page-size-partner");
    // Grant access to the share.
    grant("iceberg-tables-page-size", "iceberg-tables-page-size-partner");
    // Address the table listing endpoint.
    String tables = ICEBERG + "/v1/shares/iceberg-tables-page-size/namespaces/lake/tables";

    // Iceberg page sizes must be positive, including when the namespace has tables.
    assertIcebergError(server.get(tables + "?pageSize=0", bearer), 400, "BadRequestException");
    // Negative sizes use the same Iceberg error model.
    assertIcebergError(server.get(tables + "?pageSize=-1", bearer), 400, "BadRequestException");
  }

  @Test
  void aPageTokenBeyondTheLastTableReturnsAnEmptyPage() {
    // Create a share for an Iceberg table.
    createShare("iceberg-tables-offset");
    // Include the table under lake.
    addObject("iceberg-tables-offset", "TABLE", "main.lake.trips", "lake.trips");
    // Activate a recipient for the share.
    String bearer = createAndActivateRecipient("iceberg-tables-offset-partner");
    // Grant access to the share.
    grant("iceberg-tables-offset", "iceberg-tables-offset-partner");
    // Use Pagination's URL-safe, unpadded base64 encoding of os1:<offset>.
    String token =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("os1:2147483600".getBytes(StandardCharsets.UTF_8));

    // An offset near Integer.MAX_VALUE must not overflow while fetching the page.
    Response response =
        server.get(
            ICEBERG + "/v1/shares/iceberg-tables-offset/namespaces/lake/tables?pageToken=" + token,
            bearer);

    // A valid token beyond the end is an empty successful page.
    assertEquals(200, response.status());
    // No identifiers remain at this offset.
    assertEquals(0, response.body().path("identifiers").size());
    // An empty page has no successor.
    assertTrue(response.body().path("next-page-token").isNull());
  }
}
