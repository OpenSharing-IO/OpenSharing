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
    // Listing serves both from what the share recorded, without asking the catalog.
    Response list = server.get(PROTOCOL + "/shares/stale-tables/schemas/sales/tables", bearer);
    assertEquals(200, list.status());
    JsonNode items = list.body().path("items");
    assertEquals(2, items.size());
    JsonNode customers = items.path(0);
    assertEquals("customers", customers.path("name").asText());
    assertEquals("main.sales.customers", customers.path("id").asText());
    assertEquals("s3://test/main.sales.customers/", customers.path("location").asText());
    assertEquals("url", customers.path("accessModes").path(0).asText());
    assertEquals("dir", customers.path("accessModes").path(1).asText());
    assertEquals("orders", items.path(1).path("name").asText());
    assertEquals("other-id", items.path(1).path("id").asText());
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
    // The catalog lists the replacing schema's tables, and none for the dropped one.
    String schemas = PROTOCOL + "/shares/stale-schemas/schemas/";
    assertEquals(2, server.get(schemas + "hr/tables", bearer).body().path("items").size());
    Response dropped = server.get(schemas + "legal/tables", bearer);
    assertEquals(200, dropped.status());
    assertEquals(0, dropped.body().path("items").size());
    assertEquals(3, server.get(all, bearer).body().path("items").size());
  }

  @Test
  void reportsIncludedTablesTheCatalogReplacedOrDroppedAsMissingFromGetTable() {
    createShare("get-stale");
    addObject("get-stale", "TABLE", "main.sales.orders", "sales.orders");
    addObject("get-stale", "TABLE", "main.sales.customers", "sales.customers");
    String bearer = createAndActivateRecipient("get-stale-partner");
    grant("get-stale", "get-stale-partner");
    String tables = PROTOCOL + "/shares/get-stale/schemas/sales/tables/";
    assertEquals(200, server.get(tables + "orders", bearer).status());

    // Another table now has the name of one shared table, and another is gone from the catalog.
    editIncluded("get-stale", "main.sales.orders", o -> o.setSourceAssetId("other-id"));
    editIncluded("get-stale", "main.sales.customers", o -> o.setFullName("main.sales.dropped"));
    // Get Table resolves each in the catalog and reports both as missing, by alias.
    assertNotFound(
        server.get(tables + "orders", bearer),
        "table 'sales.orders' does not exist in share 'get-stale'");
    assertNotFound(
        server.get(tables + "customers", bearer),
        "table 'sales.customers' does not exist in share 'get-stale'");
  }

  @Test
  void reportsTablesUnderASchemaTheCatalogReplacedOrDroppedAsMissingFromGetTable() {
    createShare("get-stale-schemas");
    addObject("get-stale-schemas", "SCHEMA", "main.hr", "hr");
    addObject("get-stale-schemas", "SCHEMA", "main.legal", "legal");
    String bearer = createAndActivateRecipient("get-stale-schemas-partner");
    grant("get-stale-schemas", "get-stale-schemas-partner");
    String schemas = PROTOCOL + "/shares/get-stale-schemas/schemas/";
    assertEquals(200, server.get(schemas + "hr/tables/salaries", bearer).status());

    // Another schema now has the name of one shared schema, and another is gone from the catalog.
    editIncluded("get-stale-schemas", "main.hr", o -> o.setSourceAssetId("other-id"));
    editIncluded("get-stale-schemas", "main.legal", o -> o.setFullName("main.dropped"));
    // Get Table checks the schema before looking up its table, so both tables are missing.
    assertNotFound(
        server.get(schemas + "hr/tables/salaries", bearer),
        "table 'hr.salaries' does not exist in share 'get-stale-schemas'");
    assertNotFound(
        server.get(schemas + "legal/tables/contracts", bearer),
        "table 'legal.contracts' does not exist in share 'get-stale-schemas'");
  }

  /** A hidden table gets the protocol's not-found error, by its alias. */
  private static void assertNotFound(Response response, String message) {
    assertEquals(404, response.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, response.errorCode());
    assertEquals(message, response.body().path("message").asText());
  }
}
