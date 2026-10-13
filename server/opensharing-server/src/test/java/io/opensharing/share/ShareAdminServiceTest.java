package io.opensharing.share;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.opensharing.TestServer;
import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
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

    // Non-owner cannot update.
    Response stolen = server.patch(SHARES + "/sales", "bob-token", "{\"comment\":\"stolen\"}");
    assertEquals(403, stolen.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, stolen.errorCode());

    // Owner updates the comment.
    Response updated = server.patch(SHARES + "/sales", "alice-token", "{\"comment\":\"updated\"}");
    assertEquals(200, updated.status());
    assertEquals("updated", updated.body().path("comment").asText());

    // Owner deletes the share.
    assertEquals(204, server.delete(SHARES + "/sales", "alice-token").status());

    // Deleted share is 404.
    Response deleted = server.get(SHARES + "/sales", "alice-token");
    assertEquals(404, deleted.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, deleted.errorCode());
  }

  private static Response create(String token, String body) {
    return server.post(SHARES, token, body);
  }

  private static void assertInvalid(Response response) {
    assertEquals(400, response.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, response.errorCode());
  }
}
