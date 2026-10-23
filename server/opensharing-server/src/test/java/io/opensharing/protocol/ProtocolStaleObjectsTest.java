package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.fasterxml.jackson.databind.JsonNode;
import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import org.junit.jupiter.api.Test;

class ProtocolStaleObjectsTest extends ProtocolApiSupport {

  @Test
  void listsIncludedTablesAsTheShareRecordedThem() {
    createShare("stale-tables");
    addObject("stale-tables", "TABLE", "main.sales.orders", "sales.orders");
    addObject("stale-tables", "TABLE", "main.sales.customers", "sales.customers");
    String bearer = createAndActivateRecipient("stale-tables-partner");
    grant("stale-tables", "stale-tables-partner");

    // Another table now has the name of one shared table, and another is gone from the catalog.
    editIncluded("stale-tables", "main.sales.orders", o -> o.setSourceAssetId("other-id"));
    editIncluded("stale-tables", "main.sales.customers", o -> o.setFullName("main.sales.dropped"));
    Response list = server.get(PROTOCOL + "/shares/stale-tables/schemas/sales/tables", bearer);
    assertEquals(200, list.status());
    JsonNode items = list.body().path("items");
    assertEquals(2, items.size());
    assertEquals("customers", items.path(0).path("name").asText());
    assertEquals("s3://test/main.sales.customers/", items.path(0).path("location").asText());
    assertEquals("dir", items.path(0).path("accessModes").path(1).asText());
    assertEquals("other-id", items.path(1).path("id").asText());

    // Get Table checks the catalog.
    String tables = PROTOCOL + "/shares/stale-tables/schemas/sales/tables/";
    assertEquals(404, server.get(tables + "orders", bearer).status());
    assertEquals(404, server.get(tables + "customers", bearer).status());
  }

  @Test
  void listsTheCatalogsCurrentTablesUnderASharedSchema() {
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
    String schemas = PROTOCOL + "/shares/stale-schemas/schemas/";
    assertEquals(2, server.get(schemas + "hr/tables", bearer).body().path("items").size());
    Response dropped = server.get(schemas + "legal/tables", bearer);
    assertEquals(200, dropped.status());
    assertEquals(0, dropped.body().path("items").size());
    assertEquals(3, server.get(all, bearer).body().path("items").size());
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
