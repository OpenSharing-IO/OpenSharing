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

    Response got =
        server.get(PROTOCOL + "/shares/get-schema-table/schemas/hr/tables/salaries", bearer);
    assertEquals(200, got.status());
    assertEquals("salaries", got.body().path("name").asText());
    assertEquals("hr", got.body().path("schema").asText());
    assertEquals("main.hr.salaries", got.body().path("id").asText());
    assertEquals("delta", got.body().path("format").asText());
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

  private static void assertNotFound(Response response) {
    assertEquals(404, response.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, response.errorCode());
  }
}
