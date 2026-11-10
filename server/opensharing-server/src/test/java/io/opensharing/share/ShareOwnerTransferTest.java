package io.opensharing.share;

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
 * Transferring a share's ownership with {@code ownerId} on the update body. Only an admin of the
 * catalog may transfer, and a transfer must be the request's only change, whoever the caller is;
 * everything else follows the regular owner rules.
 */
class ShareOwnerTransferTest {

  private static final String SHARES = "/api/1.0/opensharing/provider/shares";

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
            privileges: [CREATE_SHARE, USE_SHARE]
          - bearerToken: owner-token
            userId: owner-id
            userName: owner
            privileges: [CREATE_SHARE]
          - bearerToken: other-token
            userId: other-id
            userName: other
            privileges: [CREATE_SHARE]
        assets:
          - identifier: main.sales.orders
            type: TABLE
            storageLocation: s3://test/main.sales.orders/
            format: delta
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
    assertEquals(201, create("owner-token", "handover").status());

    // The current owner may not transfer.
    Response byOwner = patch("handover", "owner-token", "{\"ownerId\":\"other-id\"}");
    assertEquals(403, byOwner.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, byOwner.errorCode());

    // Neither may a non-owner non-admin.
    Response byOther = patch("handover", "other-token", "{\"ownerId\":\"other-id\"}");
    assertEquals(403, byOther.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, byOther.errorCode());

    // Mixing the transfer with other changes is a bad request before any permission check.
    Response mixed =
        patch("handover", "other-token", "{\"ownerId\":\"other-id\",\"comment\":\"no\"}");
    assertEquals(400, mixed.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, mixed.errorCode());

    // The owner is unchanged.
    assertEquals("owner-id", owner("handover"));
  }

  @Test
  void anAdminTransfersAShareTheyDoNotOwn() {
    assertEquals(201, create("owner-token", "rescued").status());

    Response transferred = patch("rescued", "admin-token", "{\"ownerId\":\"other-id\"}");
    assertEquals(200, transferred.status(), () -> transferred.body().toString());
    assertEquals("other-id", transferred.body().path("ownerId").asText());

    // A fresh get shows the new owner too.
    assertEquals("other-id", owner("rescued"));

    // The new owner manages the share, and the old one no longer can.
    assertEquals(200, patch("rescued", "other-token", "{\"comment\":\"mine now\"}").status());
    Response oldOwner = patch("rescued", "owner-token", "{\"comment\":\"still mine\"}");
    assertEquals(403, oldOwner.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, oldOwner.errorCode());
  }

  @Test
  void anAdminWhoIsNotTheOwnerGetsNothingButTheTransfer() {
    assertEquals(201, create("owner-token", "scoped").status());

    // No other change: not the comment, not the objects, not the deletion.
    Response comment = patch("scoped", "admin-token", "{\"comment\":\"no\"}");
    assertEquals(403, comment.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, comment.errorCode());

    // A mixed request is a bad request, not a permission denial, and nothing is applied.
    Response objects =
        patch(
            "scoped",
            "admin-token",
            """
            {"ownerId":"other-id","updates":[{"action":"ADD",
              "dataObject":{"name":"main.sales.orders","type":"TABLE"}}]}
            """);
    assertEquals(400, objects.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, objects.errorCode());

    Response deleted = server.delete(SHARES + "/scoped", "admin-token");
    assertEquals(403, deleted.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, deleted.errorCode());

    // Nothing was applied.
    assertTrue(
        server.get(SHARES + "/scoped", "owner-token").body().path("comment").isMissingNode());
    assertEquals(0, objectsOf("scoped"));
  }

  @Test
  void anAdminOwnerAlsoCannotCombineTheTransferWithOtherChanges() {
    assertEquals(201, create("admin-token", "both").status());

    Response updated =
        patch(
            "both",
            "admin-token",
            """
            {"ownerId":"other-id","comment":"v2","updates":[{"action":"ADD",
              "dataObject":{"name":"main.sales.orders","type":"TABLE"}}]}
            """);
    assertEquals(400, updated.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, updated.errorCode());

    // Nothing was applied.
    assertTrue(server.get(SHARES + "/both", "admin-token").body().path("comment").isMissingNode());
    assertEquals(0, objectsOf("both"));
  }

  @Test
  void transferringToTheCurrentOwnerIsANoOp() {
    assertEquals(201, create("owner-token", "noop").status());

    Response transferred = patch("noop", "admin-token", "{\"ownerId\":\"owner-id\"}");
    assertEquals(200, transferred.status(), () -> transferred.body().toString());
    assertEquals("owner-id", owner("noop"));
  }

  private static Response create(String token, String name) {
    return server.post(SHARES, token, "{\"name\":\"" + name + "\"}");
  }

  private static Response patch(String share, String token, String body) {
    return server.patch(SHARES + "/" + share, token, body);
  }

  private static String owner(String share) {
    Response response = server.get(SHARES + "/" + share, "admin-token");
    assertEquals(200, response.status());
    return response.body().path("ownerId").asText();
  }

  private static int objectsOf(String share) {
    Response response =
        server.get(SHARES + "/" + share + "?include_shared_data=true", "admin-token");
    assertEquals(200, response.status());
    return response.body().path("objects").size();
  }
}
