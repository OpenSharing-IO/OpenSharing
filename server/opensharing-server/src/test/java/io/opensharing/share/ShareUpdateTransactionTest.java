package io.opensharing.share;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.opensharing.TestServer;
import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A share update applies its removes, adds, and field changes together or not at all. */
class ShareUpdateTransactionTest {

  private static final String SHARES = "/api/1.0/opensharing/provider/shares";
  private static final String ORDERS = "{\"name\":\"Main.Sales.Orders\",\"type\":\"TABLE\"}";

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
  void rollsBackEveryChangeWhenAnAddFails() {
    createShareWithOrders("atomic");

    // Removing orders and adding table1 succeed, but the schema over table1 then conflicts.
    Response failed =
        patch(
            "atomic",
            """
            {
              "comment": "v2",
              "updates": [
                {"action": "REMOVE", "dataObject": %s},
                {"action": "ADD", "dataObject": {"name": "main.sales.table1", "type": "TABLE"}},
                {"action": "ADD", "dataObject": {"name": "main.sales", "type": "SCHEMA"}}
              ]
            }
            """
                .formatted(ORDERS));
    assertEquals(409, failed.status(), () -> failed.body().toString());
    assertEquals(ErrorCodes.RESOURCE_CONFLICT, failed.errorCode());

    // Orders is still shared, table1 was not added, and the comment is unchanged.
    JsonNode share = share("atomic");
    assertEquals(1, share.path("objects").size());
    assertEquals("Main.Sales.Orders", share.path("objects").get(0).path("name").asText());
    assertTrue(share.path("comment").isMissingNode() || share.path("comment").isNull());
  }

  @Test
  void rollsBackWhenTheCatalogRejectsAnAdd() {
    createShareWithOrders("atomic-catalog");

    // The catalog has no main.sales.missing, so the remove before it is not applied either.
    Response failed =
        patch(
            "atomic-catalog",
            """
            {"updates": [
              {"action": "REMOVE", "dataObject": %s},
              {"action": "ADD", "dataObject": {"name": "main.sales.missing", "type": "TABLE"}}
            ]}
            """
                .formatted(ORDERS));
    assertEquals(404, failed.status(), () -> failed.body().toString());

    assertEquals(1, share("atomic-catalog").path("objects").size());
  }

  @Test
  void removesBeforeAddingWhateverTheRequestOrder() {
    createShareWithOrders("reorder");

    // The add reuses the alias of the object removed after it in the request.
    Response updated =
        patch(
            "reorder",
            """
            {"updates": [
              {"action": "ADD", "dataObject":
                {"name": "main.sales.table1", "type": "TABLE", "sharedAs": "sales.orders"}},
              {"action": "REMOVE", "dataObject": {"type": "TABLE", "sharedAs": "sales.orders"}}
            ]}
            """);
    assertEquals(200, updated.status(), () -> updated.body().toString());

    JsonNode objects = share("reorder").path("objects");
    assertEquals(1, objects.size());
    assertEquals("main.sales.table1", objects.get(0).path("name").asText());
    assertEquals("sales.orders", objects.get(0).path("sharedAs").asText());
  }

  private static void createShareWithOrders(String name) {
    assertEquals(201, server.post(SHARES, "alice-token", "{\"name\":\"" + name + "\"}").status());
    Response added =
        patch(name, "{\"updates\":[{\"action\":\"ADD\",\"dataObject\":" + ORDERS + "}]}");
    assertEquals(200, added.status(), () -> added.body().toString());
  }

  private static Response patch(String share, String body) {
    return server.patch(SHARES + "/" + share, "alice-token", body);
  }

  private static JsonNode share(String name) {
    Response response = server.get(SHARES + "/" + name + "?include_shared_data=true", "bob-token");
    assertEquals(200, response.status());
    return response.body();
  }
}
