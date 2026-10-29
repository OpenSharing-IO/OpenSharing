package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import io.opensharing.TestServer.Response;
import io.opensharing.util.ErrorCodes;
import org.junit.jupiter.api.Test;

class ProtocolStaleObjectsTest extends ProtocolApiSupport {

  @Test
  void listsSharedTablesWithoutCallingTheCatalog() {
    createShare("listed-tables");
    addObject("listed-tables", "TABLE", "main.sales.orders", "sales.orders");
    addObject("listed-tables", "TABLE", "main.sales.customers", "sales.customers");
    String recipientToken = createAndActivateRecipient("listed-tables-partner");
    grant("listed-tables", "listed-tables-partner");

    replaceInCatalog("listed-tables", "main.sales.orders");
    dropFromCatalog("listed-tables", "main.sales.customers");
    // Listing serves both from what the share recorded, without asking the catalog.
    Response salesTables =
        server.get(PROTOCOL + "/shares/listed-tables/schemas/sales/tables", recipientToken);
    assertEquals(200, salesTables.status());
    JsonNode listedTables = salesTables.body().path("items");
    assertEquals(2, listedTables.size());
    JsonNode customers = listedTables.path(0);
    assertEquals("customers", customers.path("name").asText());
    assertEquals("main.sales.customers", customers.path("id").asText());
    assertEquals("s3://test/main.sales.customers/", customers.path("location").asText());
    assertEquals("url", customers.path("accessModes").path(0).asText());
    assertEquals("dir", customers.path("accessModes").path(1).asText());
    JsonNode orders = listedTables.path(1);
    assertEquals("orders", orders.path("name").asText());
    assertEquals("replaced-id", orders.path("id").asText());
  }

  @Test
  void listsTablesUnderASharedSchemaFromTheCatalog() {
    createShare("listed-schemas");
    addObject("listed-schemas", "SCHEMA", "main.hr", "hr");
    addObject("listed-schemas", "SCHEMA", "main.legal", "legal");
    addObject("listed-schemas", "TABLE", "main.sales.orders", "sales.orders");
    String recipientToken = createAndActivateRecipient("listed-schemas-partner");
    grant("listed-schemas", "listed-schemas-partner");
    String allTablesPath = PROTOCOL + "/shares/listed-schemas/all-tables";
    assertEquals(4, server.get(allTablesPath, recipientToken).body().path("items").size());

    replaceInCatalog("listed-schemas", "main.hr");
    dropFromCatalog("listed-schemas", "main.legal");
    String schemasPath = PROTOCOL + "/shares/listed-schemas/schemas/";

    // The replaced schema lists the catalog's tables under its name.
    Response hrTables = server.get(schemasPath + "hr/tables", recipientToken);
    assertEquals(2, hrTables.body().path("items").size());

    // The dropped schema lists no tables.
    Response legalTables = server.get(schemasPath + "legal/tables", recipientToken);
    assertEquals(200, legalTables.status());
    assertEquals(0, legalTables.body().path("items").size());

    // All Tables lists the replaced schema's tables and the shared table, but not the dropped one.
    assertEquals(3, server.get(allTablesPath, recipientToken).body().path("items").size());
  }

  @Test
  void getTableReportsReplacedOrDroppedTablesAsMissing() {
    createShare("stale-tables");
    addObject("stale-tables", "TABLE", "main.sales.orders", "sales.orders");
    addObject("stale-tables", "TABLE", "main.sales.customers", "sales.customers");
    String recipientToken = createAndActivateRecipient("stale-tables-partner");
    grant("stale-tables", "stale-tables-partner");
    String salesTablesPath = PROTOCOL + "/shares/stale-tables/schemas/sales/tables/";
    assertEquals(200, server.get(salesTablesPath + "orders", recipientToken).status());

    replaceInCatalog("stale-tables", "main.sales.orders");
    dropFromCatalog("stale-tables", "main.sales.customers");
    // Get Table resolves each in the catalog and reports both as missing, by alias.
    assertNotFound(
        server.get(salesTablesPath + "orders", recipientToken),
        "table 'sales.orders' does not exist in share 'stale-tables'");
    assertNotFound(
        server.get(salesTablesPath + "customers", recipientToken),
        "table 'sales.customers' does not exist in share 'stale-tables'");
  }

  @Test
  void getTableReportsTablesUnderAReplacedOrDroppedSchemaAsMissing() {
    createShare("stale-schemas");
    addObject("stale-schemas", "SCHEMA", "main.hr", "hr");
    addObject("stale-schemas", "SCHEMA", "main.legal", "legal");
    String recipientToken = createAndActivateRecipient("stale-schemas-partner");
    grant("stale-schemas", "stale-schemas-partner");
    String schemasPath = PROTOCOL + "/shares/stale-schemas/schemas/";
    assertEquals(200, server.get(schemasPath + "hr/tables/salaries", recipientToken).status());

    // The replaced schema lists the catalog's tables under its name.
    replaceInCatalog("stale-schemas", "main.hr");
    dropFromCatalog("stale-schemas", "main.legal");
    // Get Table checks the schema before looking up its table, so both tables are missing.
    assertNotFound(
        server.get(schemasPath + "hr/tables/salaries", recipientToken),
        "table 'hr.salaries' does not exist in share 'stale-schemas'");
    assertNotFound(
        server.get(schemasPath + "legal/tables/contracts", recipientToken),
        "table 'legal.contracts' does not exist in share 'stale-schemas'");
  }

  /** A hidden table gets the protocol's not-found error, by its alias. */
  private static void assertNotFound(Response response, String message) {
    assertEquals(404, response.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, response.errorCode());
    assertEquals(message, response.body().path("message").asText());
  }
}
