package io.opensharing.share;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opensharing.TestServer;
import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SharedObjectLineageTest {

  private static final String SHARES = "/api/1.0/opensharing/provider/shares";

  private static TestServer server;

  @BeforeAll
  static void startServer() {
    server = TestServer.start();
  }

  @AfterAll
  static void stopServer() {
    server.close();
  }

  @Test
  void rejectsATableOfASharedSchemaUnderAnotherAlias() {
    createShare("lineage-schema-first");
    assertOk(add("lineage-schema-first", "main.sales", "SCHEMA", "sales"));

    // Catalog names match in any case.
    Response table = add("lineage-schema-first", "Main.Sales.Orders", "TABLE", "ops.orders");
    assertConflict(table);
    assertEquals(
        "'main.sales' is already included in share 'lineage-schema-first' as 'sales'",
        table.body().path("message").asText());

    // A table of another catalog schema is still allowed.
    assertOk(add("lineage-schema-first", "main.finance.ledger", "TABLE", "ops.ledger"));
  }

  @Test
  void rejectsASchemaWhoseTableIsSharedUnderAnotherAlias() {
    createShare("lineage-table-first");
    assertOk(add("lineage-table-first", "main.sales.orders", "TABLE", "ops.orders"));

    Response schema = add("lineage-table-first", "MAIN.SALES", "SCHEMA", "sales");
    assertConflict(schema);
    assertEquals(
        "'main.sales.orders' is already included in share 'lineage-table-first' as 'ops.orders'",
        schema.body().path("message").asText());

    // Another catalog schema is still allowed.
    assertOk(add("lineage-table-first", "main.finance", "SCHEMA", "finance"));
  }

  @Test
  void allowsReaddingAfterTheOverlappingObjectIsRemoved() {
    createShare("lineage-readd");
    assertOk(add("lineage-readd", "main.sales", "SCHEMA", "sales"));

    // Removing the schema and adding its table under another alias in one update is allowed.
    String remove =
        "{\"action\":\"REMOVE\",\"dataObject\":{\"type\":\"SCHEMA\",\"sharedAs\":\"sales\"}}";
    String body =
        "{\"updates\":["
            + remove
            + ","
            + addition("main.sales.orders", "TABLE", "ops.orders")
            + "]}";
    assertOk(server.patch(SHARES + "/lineage-readd", "alice-token", body));
  }

  private static void createShare(String name) {
    assertEquals(201, server.post(SHARES, "alice-token", "{\"name\":\"" + name + "\"}").status());
  }

  private static Response add(String share, String name, String type, String sharedAs) {
    String body = "{\"updates\":[" + addition(name, type, sharedAs) + "]}";
    return server.patch(SHARES + "/" + share, "alice-token", body);
  }

  private static String addition(String name, String type, String sharedAs) {
    String dataObject = "{\"name\":\"%s\",\"type\":\"%s\",\"sharedAs\":\"%s\"}";
    return "{\"action\":\"ADD\",\"dataObject\":" + dataObject.formatted(name, type, sharedAs) + "}";
  }

  private static void assertOk(Response response) {
    assertEquals(200, response.status(), () -> response.body().toString());
  }

  private static void assertConflict(Response response) {
    assertEquals(409, response.status(), () -> response.body().toString());
    assertEquals(ErrorCodes.RESOURCE_CONFLICT, response.errorCode());
  }
}
