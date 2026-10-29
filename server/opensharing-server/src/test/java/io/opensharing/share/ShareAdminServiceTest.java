package io.opensharing.share;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.opensharing.TestServer.Response;
import io.opensharing.TestServer;
import io.opensharing.util.ErrorCodes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ShareAdminServiceTest {

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
  void requiresACatalogAuthorizedPrincipal() {
    // Missing bearer token is unauthenticated.
    Response missing = server.get(SHARES, null);
    assertEquals(401, missing.status());
    assertEquals(ErrorCodes.UNAUTHENTICATED, missing.errorCode());

    // A token the catalog does not know is unauthenticated, and names the Bearer scheme.
    Response unknown = server.get(SHARES, "nobody-token");
    assertEquals(401, unknown.status());
    assertEquals("Bearer", unknown.headers().get("WWW-Authenticate"));
    assertEquals(ErrorCodes.UNAUTHENTICATED, unknown.errorCode());
  }

  @Test
  void rejectsADuplicateShareNameInAnyCase() {
    // Owner creates a share.
    assertEquals(201, create("alice-token", "{\"name\":\"taxes\"}").status());

    // The same name in another case already exists, even for another caller.
    Response duplicate = create("bob-token", "{\"name\":\"TAXES\"}");
    assertEquals(409, duplicate.status());
    assertEquals(ErrorCodes.RESOURCE_ALREADY_EXISTS, duplicate.errorCode());
  }

  @Test
  void rejectsInvalidShareNames() {
    // Blank names fail request validation.
    assertInvalid(create("alice-token", "{\"name\":\"\"}"));

    // Names with a space or a forward slash are rejected.
    assertInvalid(create("alice-token", "{\"name\":\"bad name\"}"));
    assertInvalid(create("alice-token", "{\"name\":\"bad/name\"}"));
  }

  @Test
  void rejectsNullPropertyValues() {
    // Creating with a null property value is rejected.
    assertInvalid(create("alice-token", "{\"name\":\"nulls\",\"properties\":{\"team\":null}}"));

    // Updating with a null property value is rejected too.
    assertEquals(201, create("alice-token", "{\"name\":\"nulls\"}").status());
    assertInvalid(
        server.patch(SHARES + "/nulls", "alice-token", "{\"properties\":{\"team\":null}}"));
  }

  @Test
  void updatesOnlyTheFieldsInTheBody() {
    // Owner creates a share with every field set.
    Response created =
        create(
            "alice-token",
            "{\"name\":\"travel\",\"displayName\":\"Travel\",\"comment\":\"v1\","
                + "\"properties\":{\"team\":\"finance\"}}");
    assertEquals(201, created.status());

    // Owner updates only the comment; the other fields are kept.
    Response updated = server.patch(SHARES + "/travel", "alice-token", "{\"comment\":\"v2\"}");
    assertEquals(200, updated.status());
    assertEquals("v2", updated.body().path("comment").asText());
    assertEquals("Travel", updated.body().path("displayName").asText());
    assertEquals("finance", updated.body().path("properties").path("team").asText());
  }

  @Test
  void onlyTheOwnerDeletesAShare() {
    // Owner creates a share.
    assertEquals(201, create("alice-token", "{\"name\":\"vendors\"}").status());

    // Non-owner cannot delete.
    Response denied = server.delete(SHARES + "/vendors", "bob-token");
    assertEquals(403, denied.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, denied.errorCode());

    // The share is still there.
    assertEquals(200, server.get(SHARES + "/vendors", "bob-token").status());
  }

  @Test
  void answersMissingSharesWithNotFound() {
    // Updating a missing share is 404.
    Response update = server.patch(SHARES + "/missing", "alice-token", "{\"comment\":\"x\"}");
    assertEquals(404, update.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, update.errorCode());

    // Deleting a missing share is 404.
    Response delete = server.delete(SHARES + "/missing", "alice-token");
    assertEquals(404, delete.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, delete.errorCode());
  }

  @Test
  void createsListsUpdatesAndDeletesAShare() {
    // Owner creates a share; name is persisted lowercase.
    Response created = create("alice-token", "{\"name\":\"Sales\",\"comment\":\"orders\"}");
    assertEquals(201, created.status());
    assertEquals("sales", created.body().path("name").asText());
    assertFalse(created.body().path("id").isMissingNode());

    // Owner lists shares.
    Response list = server.get(SHARES, "alice-token");
    assertEquals(200, list.status());
    assertEquals(
        1,
        list.body().path("items").findValuesAsText("name").stream()
            .filter("sales"::equals)
            .count());

    // Get by name is case-insensitive and allowed for any catalog principal.
    Response fetched = server.get(SHARES + "/SALES", "bob-token");
    assertEquals(200, fetched.status());
    assertEquals("sales", fetched.body().path("name").asText());
    assertEquals("orders", fetched.body().path("comment").asText());
    assertTrue(fetched.body().path("objects").isMissingNode());

    // include_shared_data returns objects; none are shared yet.
    assertEquals(0, objects("sales").size());

    // Non-owner cannot update.
    Response stolen = server.patch(SHARES + "/sales", "bob-token", "{\"comment\":\"stolen\"}");
    assertEquals(403, stolen.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, stolen.errorCode());

    // Owner updates the comment and adds a table.
    Response updated =
        patch(
            "sales",
            """
            {
              "comment": "updated",
              "updates": [{
                "action": "ADD",
                "dataObject": {"name": "Main.Sales.Orders", "type": "TABLE"}
              }]
            }
            """);
    assertEquals(200, updated.status());
    assertEquals("updated", updated.body().path("comment").asText());

    // Default GET omits objects even after they are shared.
    assertTrue(server.get(SHARES + "/sales", "bob-token").body().path("objects").isMissingNode());

    // include_shared_data returns the added table under its default alias.
    JsonNode objects = objects("sales");
    assertEquals(1, objects.size());
    assertEquals("Main.Sales.Orders", objects.get(0).path("name").asText());
    assertEquals("TABLE", objects.get(0).path("type").asText());
    assertEquals("sales.orders", objects.get(0).path("sharedAs").asText());

    // Owner removes the table by sharedAs, in any case.
    assertOk(update("sales", "REMOVE", "{\"type\":\"TABLE\",\"sharedAs\":\"SALES.ORDERS\"}"));
    assertEquals(0, objects("sales").size());

    // A schema is shared under the last segment of its name.
    assertOk(update("sales", "ADD", "{\"name\":\"Main.Sales\",\"type\":\"SCHEMA\"}"));
    assertEquals("sales", objects("sales").get(0).path("sharedAs").asText());

    // Owner removes the schema by sharedAs; the catalog segment is dropped.
    assertOk(update("sales", "REMOVE", "{\"type\":\"SCHEMA\",\"sharedAs\":\"MAIN.SALES\"}"));
    assertEquals(0, objects("sales").size());

    // Owner adds a table under an explicit alias, which is lowercased.
    assertOk(
        update(
            "sales",
            "ADD",
            "{\"name\":\"main.sales.orders\",\"type\":\"TABLE\",\"sharedAs\":\"Sales.Orders\"}"));
    assertEquals("sales.orders", objects("sales").get(0).path("sharedAs").asText());

    // Owner deletes the share and the objects in it.
    assertEquals(204, server.delete(SHARES + "/sales", "alice-token").status());

    // Deleted share is 404.
    Response deleted = server.get(SHARES + "/sales", "alice-token");
    assertEquals(404, deleted.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, deleted.errorCode());
  }

  @Test
  void rejectsUnsupportedAssetTypes() {
    // VOLUME is not a type a share can hold.
    createShare("edge-volume");
    Response volume =
        update("edge-volume", "ADD", "{\"name\":\"main.sales.orders\",\"type\":\"VOLUME\"}");
    assertInvalid(volume);
    assertEquals(
        "updates[0].dataObject.type must be one of [TABLE, SCHEMA]",
        volume.body().path("message").asText());
  }

  @Test
  void rejectsTableNamesWithoutASchema() {
    // A TABLE name must include a schema segment.
    createShare("edge-nodot");
    assertInvalid(update("edge-nodot", "ADD", "{\"name\":\"Orders\",\"type\":\"TABLE\"}"));
  }

  @Test
  void rejectsATableUnderASharedSchema() {
    createShare("edge-schema-table");
    assertOk(update("edge-schema-table", "ADD", "{\"name\":\"Main.Sales\",\"type\":\"SCHEMA\"}"));
    assertConflict(
        update("edge-schema-table", "ADD", "{\"name\":\"Main.Sales.Orders\",\"type\":\"TABLE\"}"),
        ErrorCodes.RESOURCE_CONFLICT);
  }

  @Test
  void rejectsASchemaOverSharedTables() {
    createShare("edge-table-schema");
    assertOk(
        update("edge-table-schema", "ADD", "{\"name\":\"Main.Sales.Orders\",\"type\":\"TABLE\"}"));
    assertConflict(
        update("edge-table-schema", "ADD", "{\"name\":\"Main.Sales\",\"type\":\"SCHEMA\"}"),
        ErrorCodes.RESOURCE_CONFLICT);
  }

  @Test
  void rejectsDuplicateAliasAndSource() {
    // First ADD of the catalog table succeeds.
    createShare("edge-dup");
    String orders = "{\"name\":\"Main.Sales.Orders\",\"type\":\"TABLE\"}";
    assertOk(update("edge-dup", "ADD", orders));

    // The same catalog table cannot be added twice.
    assertConflict(update("edge-dup", "ADD", orders), ErrorCodes.RESOURCE_ALREADY_EXISTS);

    // The same catalog table under a different alias is still a duplicate source.
    assertConflict(
        update(
            "edge-dup",
            "ADD",
            "{\"name\":\"Main.Sales.Orders\",\"type\":\"TABLE\",\"sharedAs\":\"other.orders\"}"),
        ErrorCodes.RESOURCE_ALREADY_EXISTS);
  }

  @Test
  void removeRequiresTypeAndAnExistingObject() {
    // ADD a table to remove later.
    createShare("edge-remove");
    String orders = "{\"name\":\"Main.Sales.Orders\",\"type\":\"TABLE\"}";
    assertOk(update("edge-remove", "ADD", orders));

    // REMOVE without a type is invalid.
    assertInvalid(update("edge-remove", "REMOVE", "{\"sharedAs\":\"sales.orders\"}"));

    // REMOVE of a missing alias is 404.
    Response missing =
        update("edge-remove", "REMOVE", "{\"type\":\"TABLE\",\"sharedAs\":\"missing.table\"}");
    assertEquals(404, missing.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, missing.errorCode());

    // REMOVE by catalog name succeeds.
    assertOk(update("edge-remove", "REMOVE", orders));
  }

  @Test
  void rejectsNullUpdates() {
    createShare("edge-null");
    Response response = patch("edge-null", "{\"updates\":[null]}");
    assertInvalid(response);
    assertEquals("updates must not contain null", response.body().path("message").asText());
  }

  private static Response create(String token, String body) {
    return server.post(SHARES, token, body);
  }

  private static void createShare(String name) {
    assertEquals(201, create("alice-token", "{\"name\":\"" + name + "\"}").status());
  }

  private static Response patch(String share, String body) {
    return server.patch(SHARES + "/" + share, "alice-token", body);
  }

  // Applies a single object update as the owner.
  private static Response update(String share, String action, String dataObject) {
    return patch(
        share,
        "{\"updates\":[{\"action\":\"" + action + "\",\"dataObject\":" + dataObject + "}]}");
  }

  private static JsonNode objects(String share) {
    Response response = server.get(SHARES + "/" + share + "?include_shared_data=true", "bob-token");
    assertEquals(200, response.status());
    return response.body().path("objects");
  }

  private static void assertOk(Response response) {
    assertEquals(200, response.status(), () -> response.body().toString());
  }

  private static void assertConflict(Response response, String errorCode) {
    assertEquals(409, response.status());
    assertEquals(errorCode, response.errorCode());
  }

  private static void assertInvalid(Response response) {
    assertEquals(400, response.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, response.errorCode());
  }
}
