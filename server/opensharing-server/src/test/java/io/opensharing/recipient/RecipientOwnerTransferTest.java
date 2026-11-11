package io.opensharing.recipient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.TestServer;
import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Transferring a recipient's ownership with {@code ownerId} on the update body, under the same
 * rules as for shares: only an admin of the catalog may transfer, and a transfer must be the
 * request's only change, whoever the caller is.
 */
class RecipientOwnerTransferTest {

  private static final String RECIPIENTS = "/api/1.0/opensharing/provider/recipients";

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
    create("bob-token", "handover");

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
  void anAdminTransfersARecipientTheyDoNotOwn() {
    create("bob-token", "rescued");

    Response transferred = patch("rescued", "alice-token", "{\"ownerId\":\"catalog-carol-id\"}");
    assertEquals(200, transferred.status(), () -> transferred.body().toString());
    assertEquals("catalog-carol-id", transferred.body().path("ownerId").asText());

    // A fresh get shows the new owner too.
    assertEquals("catalog-carol-id", owner("rescued"));

    // The new owner manages the recipient, and the old one no longer can.
    assertEquals(200, patch("rescued", "carol-token", "{\"comment\":\"mine now\"}").status());
    Response oldOwner = patch("rescued", "bob-token", "{\"comment\":\"still mine\"}");
    assertEquals(403, oldOwner.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, oldOwner.errorCode());
  }

  @Test
  void anAdminWhoIsNotTheOwnerGetsNothingButTheTransfer() {
    create("bob-token", "scoped");

    // No other change: not the comment, not the deletion, not the rotation.
    Response comment = patch("scoped", "alice-token", "{\"comment\":\"no\"}");
    assertEquals(403, comment.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, comment.errorCode());

    // A mixed request is a bad request, not a permission denial, and nothing is applied.
    Response mixed =
        patch("scoped", "alice-token", "{\"ownerId\":\"catalog-carol-id\",\"comment\":\"no\"}");
    assertEquals(400, mixed.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, mixed.errorCode());

    Response deleted = server.delete(RECIPIENTS + "/scoped", "alice-token");
    assertEquals(403, deleted.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, deleted.errorCode());

    Response rotated =
        server.post(
            RECIPIENTS + "/scoped/rotate-token", "alice-token", "{\"tokenExpirationDays\":1}");
    assertEquals(403, rotated.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, rotated.errorCode());

    // Nothing was applied.
    assertTrue(
        server.get(RECIPIENTS + "/scoped", "alice-token").body().path("comment").isMissingNode());
    assertEquals("catalog-bob-id", owner("scoped"));
  }

  @Test
  void anAdminOwnerAlsoCannotCombineTheTransferWithOtherChanges() {
    create("alice-token", "both");

    Response updated =
        patch("both", "alice-token", "{\"ownerId\":\"catalog-carol-id\",\"comment\":\"v2\"}");
    assertEquals(400, updated.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, updated.errorCode());

    // Nothing was applied.
    assertTrue(
        server.get(RECIPIENTS + "/both", "alice-token").body().path("comment").isMissingNode());
    assertEquals("catalog-alice-id", owner("both"));
  }

  @Test
  void anOwnerLongerThanTheColumnIsRejected() {
    create("alice-token", "long-owner");

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

  @Test
  void transferringToTheCurrentOwnerIsANoOp() {
    create("bob-token", "noop");

    Response transferred = patch("noop", "alice-token", "{\"ownerId\":\"catalog-bob-id\"}");
    assertEquals(200, transferred.status(), () -> transferred.body().toString());
    assertEquals("catalog-bob-id", owner("noop"));
  }

  private static void create(String token, String name) {
    Response created =
        server.post(
            RECIPIENTS,
            token,
            "{\"name\":\""
                + name
                + "\",\"authenticationType\":\"TOKEN\",\"tokenExpirationDays\":30}");
    assertEquals(201, created.status(), () -> created.body().toString());
  }

  private static Response patch(String recipient, String token, String body) {
    return server.patch(RECIPIENTS + "/" + recipient, token, body);
  }

  private static String owner(String recipient) {
    Response response = server.get(RECIPIENTS + "/" + recipient, "alice-token");
    assertEquals(200, response.status());
    return response.body().path("ownerId").asText();
  }
}
