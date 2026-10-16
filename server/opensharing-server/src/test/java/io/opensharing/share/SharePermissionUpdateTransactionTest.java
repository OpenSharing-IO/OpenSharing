package io.opensharing.share;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opensharing.TestServer;
import io.opensharing.TestServer.Response;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A permissions update applies all of its grants and revokes, or none of them. */
class SharePermissionUpdateTransactionTest {

  private static final String SHARES = "/api/1.0/opensharing/provider/shares";
  private static final String RECIPIENTS = "/api/1.0/opensharing/provider/recipients";

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
  void rollsBackEveryChangeWhenARevokeFails() {
    createShareGrantedTo("atomic", "atomic-a", "atomic-b", "atomic-c");

    // Revoking from a and granting to b succeed, but c holds nothing to revoke.
    Response failed =
        permissions(
            "atomic",
            """
            {"changes": [
              {"recipientName": "atomic-a", "remove": ["SELECT"]},
              {"recipientName": "atomic-b", "add": ["SELECT"]},
              {"recipientName": "atomic-c", "remove": ["SELECT"]}
            ]}
            """);
    assertEquals(404, failed.status(), () -> failed.body().toString());

    // a still holds SELECT and b was not granted it.
    assertEquals(List.of("atomic-a"), holders("atomic"));
  }

  @Test
  void rollsBackWhenARecipientDoesNotExist() {
    createShareGrantedTo("missing", "missing-a", "missing-b");

    Response failed =
        permissions(
            "missing",
            """
            {"changes": [
              {"recipientName": "missing-a", "remove": ["SELECT"]},
              {"recipientName": "missing-b", "add": ["SELECT"]},
              {"recipientName": "nobody", "add": ["SELECT"]}
            ]}
            """);
    assertEquals(404, failed.status(), () -> failed.body().toString());

    assertEquals(List.of("missing-a"), holders("missing"));
  }

  // Creates the share and recipients, and grants SELECT to the first recipient only.
  private static void createShareGrantedTo(String share, String... recipients) {
    assertEquals(201, server.post(SHARES, "alice-token", "{\"name\":\"" + share + "\"}").status());
    for (String recipient : recipients) {
      String body =
          "{\"name\":\""
              + recipient
              + "\",\"authenticationType\":\"TOKEN\",\"tokenExpirationDays\":30}";
      assertEquals(201, server.post(RECIPIENTS, "alice-token", body).status());
    }
    Response granted =
        permissions(
            share,
            "{\"changes\":[{\"recipientName\":\"" + recipients[0] + "\",\"add\":[\"SELECT\"]}]}");
    assertEquals(200, granted.status(), () -> granted.body().toString());
  }

  private static Response permissions(String share, String body) {
    return server.patch(SHARES + "/" + share + "/permissions", "alice-token", body);
  }

  private static List<String> holders(String share) {
    Response response = server.get(SHARES + "/" + share + "/permissions", "alice-token");
    assertEquals(200, response.status());
    return response.body().path("items").findValuesAsText("recipientName");
  }
}
