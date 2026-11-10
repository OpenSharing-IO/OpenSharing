package io.opensharing.share;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.TestServer;
import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Transferring a share's ownership with {@code ownerId} on the update body. Only an admin of the
 * catalog may transfer, and a transfer must be the request's only change, whoever the caller is;
 * everything else follows the regular owner rules.
 */
class ShareOwnerTransferTest {

  private static final String SHARES = "/api/1.0/opensharing/provider/shares";

  private static TestServer server;

  @BeforeAll
  static void startServer() {
    // The shipped catalog config: alice is the admin, bob and carol are plain users.
    server = TestServer.start();
  }

  @AfterAll
  static void stopServer() {
    server.close();
  }

  @Test
  void onlyAnAdminMayTransferAnOwnership() {
    assertEquals(201, create("bob-token", "handover").status());

    // The current owner may not transfer.
    Response byOwner = patch("handover", "bob-token", "{\"ownerId\":\"catalog-carol-id\"}");
    assertEquals(403, byOwner.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, byOwner.errorCode());

    // Neither may a non-owner non-admin.
    Response byOther = patch("handover", "carol-token", "{\"ownerId\":\"catalog-carol-id\"}");
    assertEquals(403, byOther.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, byOther.errorCode());

    // Mixing the transfer with other changes is a bad request before any permission check.
    Response mixed =
        patch("handover", "carol-token", "{\"ownerId\":\"catalog-carol-id\",\"comment\":\"no\"}");
    assertEquals(400, mixed.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, mixed.errorCode());

    // The owner is unchanged.
    assertEquals("catalog-bob-id", owner("handover"));
  }

  @Test
  void anAdminTransfersAShareTheyDoNotOwn() {
    assertEquals(201, create("bob-token", "rescued").status());

    Response transferred = patch("rescued", "alice-token", "{\"ownerId\":\"catalog-carol-id\"}");
    assertEquals(200, transferred.status(), () -> transferred.body().toString());
    assertEquals("catalog-carol-id", transferred.body().path("ownerId").asText());

    // A fresh get shows the new owner too.
    assertEquals("catalog-carol-id", owner("rescued"));

    // The new owner manages the share, and the old one no longer can.
    assertEquals(200, patch("rescued", "carol-token", "{\"comment\":\"mine now\"}").status());
    Response oldOwner = patch("rescued", "bob-token", "{\"comment\":\"still mine\"}");
    assertEquals(403, oldOwner.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, oldOwner.errorCode());
  }

  @Test
  void anAdminWhoIsNotTheOwnerGetsNothingButTheTransfer() {
    assertEquals(201, create("bob-token", "scoped").status());

    // No other change: not the comment, not the objects, not the deletion.
    Response comment = patch("scoped", "alice-token", "{\"comment\":\"no\"}");
    assertEquals(403, comment.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, comment.errorCode());

    // A mixed request is a bad request, not a permission denial, and nothing is applied.
    Response objects =
        patch(
            "scoped",
            "alice-token",
            """
            {"ownerId":"catalog-carol-id","updates":[{"action":"ADD",
              "dataObject":{"name":"main.sales.orders","type":"TABLE"}}]}
            """);
    assertEquals(400, objects.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, objects.errorCode());

    Response deleted = server.delete(SHARES + "/scoped", "alice-token");
    assertEquals(403, deleted.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, deleted.errorCode());

    // Nothing was applied.
    assertTrue(
        server.get(SHARES + "/scoped", "bob-token").body().path("comment").isMissingNode());
    assertEquals(0, objectsOf("scoped"));
  }

  @Test
  void anAdminOwnerAlsoCannotCombineTheTransferWithOtherChanges() {
    assertEquals(201, create("alice-token", "both").status());

    Response updated =
        patch(
            "both",
            "alice-token",
            """
            {"ownerId":"catalog-carol-id","comment":"v2","updates":[{"action":"ADD",
              "dataObject":{"name":"main.sales.orders","type":"TABLE"}}]}
            """);
    assertEquals(400, updated.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, updated.errorCode());

    // Nothing was applied.
    assertTrue(server.get(SHARES + "/both", "alice-token").body().path("comment").isMissingNode());
    assertEquals(0, objectsOf("both"));
  }

  @Test
  void transferringToTheCurrentOwnerIsANoOp() {
    assertEquals(201, create("bob-token", "noop").status());

    Response transferred = patch("noop", "alice-token", "{\"ownerId\":\"catalog-bob-id\"}");
    assertEquals(200, transferred.status(), () -> transferred.body().toString());
    assertEquals("catalog-bob-id", owner("noop"));
  }

  @Test
  void anOwnerLongerThanTheColumnIsRejected() {
    assertEquals(201, create("alice-token", "long-owner").status());

    String longOwnerId = "x".repeat(256);
    Response rejected =
        patch("long-owner", "alice-token", "{\"ownerId\":\"" + longOwnerId + "\"}");
    assertEquals(400, rejected.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, rejected.errorCode());
    assertEquals(
        "ownerId must not exceed 255 characters", rejected.body().path("message").asText());

    // Nothing was applied.
    assertEquals("catalog-alice-id", owner("long-owner"));
  }

  private static Response create(String token, String name) {
    return server.post(SHARES, token, "{\"name\":\"" + name + "\"}");
  }

  private static Response patch(String share, String token, String body) {
    return server.patch(SHARES + "/" + share, token, body);
  }

  private static String owner(String share) {
    Response response = server.get(SHARES + "/" + share, "alice-token");
    assertEquals(200, response.status());
    return response.body().path("ownerId").asText();
  }

  private static int objectsOf(String share) {
    Response response =
        server.get(SHARES + "/" + share + "?include_shared_data=true", "alice-token");
    assertEquals(200, response.status());
    return response.body().path("objects").size();
  }
}
