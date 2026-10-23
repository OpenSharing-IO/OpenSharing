package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import org.junit.jupiter.api.Test;

class ProtocolGetTableTest extends ProtocolApiSupport {

  @Test
  void getsAnIncludedTable() {
    createShare("get-table");
    addObject("get-table", "TABLE", "main.sales.orders", "sales.orders");
    String bearer = createAndActivateRecipient("get-table-partner");
    grant("get-table", "get-table-partner");

    // Share, schema and table lookups are case-insensitive.
    Response got = server.get(PROTOCOL + "/shares/GET-TABLE/schemas/SALES/tables/ORDERS", bearer);
    assertEquals(200, got.status());
    JsonNode table = got.body();
    assertEquals("orders", table.path("name").asText());
    assertEquals("sales", table.path("schema").asText());
    assertEquals("get-table", table.path("share").asText());
    assertFalse(table.path("shareId").asText().isEmpty());
    assertEquals("main.sales.orders", table.path("id").asText());
    assertEquals("delta", table.path("format").asText());
    assertTrue(table.path("location").isMissingNode());
  }

  @Test
  void getsACatalogChildOfASharedSchema() {
    createShare("get-schema-table");
    addObject("get-schema-table", "SCHEMA", "main.hr", "hr");
    String bearer = createAndActivateRecipient("get-schema-table-partner");
    grant("get-schema-table", "get-schema-table-partner");

    // The table is looked up in the catalog, which matches its name in any case.
    Response got =
        server.get(PROTOCOL + "/shares/get-schema-table/schemas/HR/tables/Salaries", bearer);
    assertEquals(200, got.status());
    assertEquals("salaries", got.body().path("name").asText());
    assertEquals("hr", got.body().path("schema").asText());
    assertEquals("main.hr.salaries", got.body().path("id").asText());
    assertEquals("delta", got.body().path("format").asText());
  }

  @Test
  void namesACatalogChildOfASharedSchemaByLowercaseAlias() {
    createShare("get-cased-table");
    addObject("get-cased-table", "SCHEMA", "main.legal", "legal");
    String bearer = createAndActivateRecipient("get-cased-table-partner");
    grant("get-cased-table", "get-cased-table-partner");

    Response got =
        server.get(PROTOCOL + "/shares/get-cased-table/schemas/legal/tables/contracts", bearer);
    assertEquals(200, got.status());
    assertEquals("contracts", got.body().path("name").asText());
    assertEquals("legal", got.body().path("schema").asText());
  }

  @Test
  void reportsMissingTablesSchemasAndShares() {
    createShare("get-missing");
    addObject("get-missing", "TABLE", "main.sales.orders", "sales.orders");
    createShare("get-hidden");
    addObject("get-hidden", "TABLE", "main.sales.orders", "sales.orders");
    String bearer = createAndActivateRecipient("get-missing-partner");
    grant("get-missing", "get-missing-partner");

    String tables = PROTOCOL + "/shares/get-missing/schemas/";
    assertNotFound(server.get(tables + "sales/tables/customers", bearer));
    assertNotFound(server.get(tables + "missing/tables/orders", bearer));
    // An ungranted share is reported as missing.
    assertNotFound(server.get(PROTOCOL + "/shares/get-hidden/schemas/sales/tables/orders", bearer));
  }

  @Test
  void getsATableOfAnAliasedSchemaAndReportsAMissingOneByItsAlias() {
    createShare("get-schema-missing");
    addObject("get-schema-missing", "SCHEMA", "main.hr", "people");
    String bearer = createAndActivateRecipient("get-schema-missing-partner");
    grant("get-schema-missing", "get-schema-missing-partner");

    String tables = PROTOCOL + "/shares/get-schema-missing/schemas/people/tables/";
    Response got = server.get(tables + "salaries", bearer);
    assertEquals(200, got.status());
    assertEquals("salaries", got.body().path("name").asText());
    assertEquals("people", got.body().path("schema").asText());
    assertEquals("main.hr.salaries", got.body().path("id").asText());

    Response missing = server.get(tables + "bonuses", bearer);
    assertNotFound(missing);
    assertEquals(
        "table 'people.bonuses' does not exist in share 'get-schema-missing'",
        missing.body().path("message").asText());
    // Only tables directly in the schema are on the listing.
    assertNotFound(server.get(tables + "salaries.history", bearer));
  }

  /** Missing tables, schemas and shares all get the protocol's not-found error. */
  private static void assertNotFound(Response response) {
    assertEquals(404, response.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, response.errorCode());
  }
}
