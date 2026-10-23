package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import org.junit.jupiter.api.Test;

class ProtocolStaleObjectsTest extends ProtocolApiSupport {

  @Test
  void hidesIncludedTablesTheCatalogReplacedOrDropped() {
    createShare("stale-tables");
    addObject("stale-tables", "TABLE", "main.sales.orders", "sales.orders");
    addObject("stale-tables", "TABLE", "main.sales.customers", "sales.customers");
    addObject("stale-tables", "TABLE", "main.ops.people", "ops.people");
    String bearer = createAndActivateRecipient("stale-tables-partner");
    grant("stale-tables", "stale-tables-partner");
    String sales = PROTOCOL + "/shares/stale-tables/schemas/sales/tables";
    assertEquals(2, server.get(sales, bearer).body().path("items").size());

    // Another table now has the name of one shared table, and another is gone from the catalog.
    editIncluded("stale-tables", "main.sales.orders", o -> o.setSourceAssetId("other-id"));
    editIncluded("stale-tables", "main.sales.customers", o -> o.setFullName("main.sales.dropped"));
    Response hidden = server.get(sales, bearer);
    assertEquals(200, hidden.status());
    assertEquals(0, hidden.body().path("items").size());
    assertTrue(hidden.body().path("nextPageToken").isMissingNode());

    String all = PROTOCOL + "/shares/stale-tables/all-tables";
    JsonNode items = server.get(all, bearer).body().path("items");
    assertEquals(1, items.size());
    assertEquals("people", items.path(0).path("name").asText());

    // A page keeps its place in the share even when its tables are hidden.
    Response first = server.get(page(all, 2, null), bearer);
    assertEquals(1, first.body().path("items").size());
    String pageToken = first.body().path("nextPageToken").asText();
    assertFalse(pageToken.isEmpty());
    Response second = server.get(page(all, 2, pageToken), bearer);
    assertEquals(200, second.status());
    assertEquals(0, second.body().path("items").size());
    assertTrue(second.body().path("nextPageToken").isMissingNode());
  }

  @Test
  void hidesTheTablesOfASchemaTheCatalogReplacedOrDropped() {
    createShare("stale-schemas");
    addObject("stale-schemas", "SCHEMA", "main.hr", "hr");
    addObject("stale-schemas", "SCHEMA", "main.legal", "legal");
    addObject("stale-schemas", "TABLE", "main.sales.orders", "sales.orders");
    String bearer = createAndActivateRecipient("stale-schemas-partner");
    grant("stale-schemas", "stale-schemas-partner");
    String all = PROTOCOL + "/shares/stale-schemas/all-tables";
    assertEquals(4, server.get(all, bearer).body().path("items").size());

    // Another schema now has the name of one shared schema, and another is gone from the catalog.
    editIncluded("stale-schemas", "main.hr", o -> o.setSourceAssetId("other-id"));
    editIncluded("stale-schemas", "main.legal", o -> o.setFullName("main.dropped"));
    for (String schema : new String[] {"hr", "legal"}) {
      Response hidden =
          server.get(PROTOCOL + "/shares/stale-schemas/schemas/" + schema + "/tables", bearer);
      assertEquals(200, hidden.status());
      assertEquals(0, hidden.body().path("items").size());
    }
    JsonNode items = server.get(all, bearer).body().path("items");
    assertEquals(1, items.size());
    assertEquals("orders", items.path(0).path("name").asText());
  }

  @Test
  void reportsAnIncludedTableTheCatalogReplacedAsMissingFromGetTable() {
    createShare("get-replaced");
    addObject("get-replaced", "TABLE", "main.sales.orders", "sales.orders");
    String bearer = createAndActivateRecipient("get-replaced-partner");
    grant("get-replaced", "get-replaced-partner");
    String orders = PROTOCOL + "/shares/get-replaced/schemas/sales/tables/orders";
    assertEquals(200, server.get(orders, bearer).status());

    editIncluded("get-replaced", "main.sales.orders", o -> o.setSourceAssetId("other-id"));
    assertNotFound(
        server.get(orders, bearer), "table 'sales.orders' does not exist in share 'get-replaced'");
  }

  @Test
  void reportsATableUnderASchemaTheCatalogReplacedAsMissingFromGetTable() {
    createShare("get-replaced-schema");
    addObject("get-replaced-schema", "SCHEMA", "main.hr", "hr");
    String bearer = createAndActivateRecipient("get-replaced-schema-partner");
    grant("get-replaced-schema", "get-replaced-schema-partner");
    String salaries = PROTOCOL + "/shares/get-replaced-schema/schemas/hr/tables/salaries";
    assertEquals(200, server.get(salaries, bearer).status());

    editIncluded("get-replaced-schema", "main.hr", o -> o.setSourceAssetId("other-id"));
    assertNotFound(
        server.get(salaries, bearer),
        "table 'hr.salaries' does not exist in share 'get-replaced-schema'");
  }

  /** A hidden table gets the protocol's not-found error, by its alias. */
  private static void assertNotFound(Response response, String message) {
    assertEquals(404, response.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, response.errorCode());
    assertEquals(message, response.body().path("message").asText());
  }
}
