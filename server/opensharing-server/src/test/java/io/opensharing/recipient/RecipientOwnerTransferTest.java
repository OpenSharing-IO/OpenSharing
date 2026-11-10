package io.opensharing.recipient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.TestServer;
import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Transferring a recipient's ownership with {@code owner} on the update body, under the same rules
 * as for shares: only an admin of the catalog may transfer, and only in a request whose owner is
 * the sole change unless the caller also owns the recipient.
 */
class RecipientOwnerTransferTest {

  private static final String RECIPIENTS = "/api/1.0/opensharing/provider/recipients";

  @TempDir
  static Path directory;

  private static TestServer server;

  @BeforeAll
  static void startServer() throws IOException {
    String catalog =
        """
        credentials:
          provider: AWS
        adminUserIds:
          - admin-id
        principals:
          - bearerToken: admin-token
            userId: admin-id
            userName: admin
            privileges: [CREATE_SHARE, CREATE_RECIPIENT, USE_SHARE, USE_RECIPIENT]
          - bearerToken: owner-token
            userId: owner-id
            userName: owner
            privileges: [CREATE_SHARE, CREATE_RECIPIENT]
          - bearerToken: other-token
            userId: other-id
            userName: other
            privileges: [CREATE_SHARE, CREATE_RECIPIENT]
        """;
    Path file = Files.writeString(directory.resolve("local-catalog.yml"), catalog);
    Properties properties = new Properties();
    properties.setProperty("catalog.local.file", file.toString());
    server = TestServer.start(properties);
  }

  @AfterAll
  static void stopServer() {
    server.close();
  }

  @Test
  void onlyAnAdminMayTransferAnOwnership() {
    create("owner-token", "handover");

    // The current owner may not transfer.
    Response byOwner = patch("handover", "owner-token", "{\"owner\":\"other-id\"}");
    assertEquals(403, byOwner.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, byOwner.errorCode());

    // Neither may a non-owner non-admin.
    Response byOther = patch("handover", "other-token", "{\"owner\":\"other-id\"}");
    assertEquals(403, byOther.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, byOther.errorCode());

    // The owner is unchanged.
    assertEquals("owner-id", owner("handover"));
  }

  @Test
  void anAdminTransfersARecipientTheyDoNotOwn() {
    create("owner-token", "rescued");

    Response transferred = patch("rescued", "admin-token", "{\"owner\":\"other-id\"}");
    assertEquals(200, transferred.status(), () -> transferred.body().toString());
    assertEquals("other-id", transferred.body().path("owner").asText());

    // A fresh get shows the new owner too.
    assertEquals("other-id", owner("rescued"));

    // The new owner manages the recipient, and the old one no longer can.
    assertEquals(200, patch("rescued", "other-token", "{\"comment\":\"mine now\"}").status());
    Response oldOwner = patch("rescued", "owner-token", "{\"comment\":\"still mine\"}");
    assertEquals(403, oldOwner.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, oldOwner.errorCode());
  }

  @Test
  void anAdminWhoIsNotTheOwnerGetsNothingButTheTransfer() {
    create("owner-token", "scoped");

    // No other change: not the comment, not the deletion, not the rotation.
    Response comment = patch("scoped", "admin-token", "{\"comment\":\"no\"}");
    assertEquals(403, comment.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, comment.errorCode());

    Response mixed = patch("scoped", "admin-token", "{\"owner\":\"other-id\",\"comment\":\"no\"}");
    assertEquals(403, mixed.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, mixed.errorCode());

    Response deleted = server.delete(RECIPIENTS + "/scoped", "admin-token");
    assertEquals(403, deleted.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, deleted.errorCode());

    Response rotated =
        server.post(RECIPIENTS + "/scoped/rotate-token", "admin-token", "{\"tokenExpirationDays\":1}");
    assertEquals(403, rotated.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, rotated.errorCode());

    // Nothing was applied.
    assertTrue(
        server.get(RECIPIENTS + "/scoped", "admin-token").body().path("comment").isMissingNode());
    assertEquals("owner-id", owner("scoped"));
  }

  @Test
  void anAdminWhoOwnsTheRecipientMayCombineTheTransferWithOtherChanges() {
    create("admin-token", "both");

    Response updated = patch("both", "admin-token", "{\"owner\":\"other-id\",\"comment\":\"v2\"}");
    assertEquals(200, updated.status(), () -> updated.body().toString());
    assertEquals("other-id", updated.body().path("owner").asText());
    assertEquals("v2", updated.body().path("comment").asText());
  }

  @Test
  void anUnknownNewOwnerIsRejected() {
    create("owner-token", "vetted");

    Response unknown = patch("vetted", "admin-token", "{\"owner\":\"ghost-id\"}");
    assertEquals(400, unknown.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, unknown.errorCode());

    // The owner is unchanged.
    assertEquals("owner-id", owner("vetted"));
  }

  @Test
  void transferringToTheCurrentOwnerIsANoOp() {
    create("owner-token", "noop");

    Response transferred = patch("noop", "admin-token", "{\"owner\":\"owner-id\"}");
    assertEquals(200, transferred.status(), () -> transferred.body().toString());
    assertEquals("owner-id", owner("noop"));
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
    Response response = server.get(RECIPIENTS + "/" + recipient, "admin-token");
    assertEquals(200, response.status());
    return response.body().path("owner").asText();
  }
}
