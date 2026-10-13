package io.opensharing.recipient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.TestServer;
import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RecipientAdminServiceTest {

  private static final String RECIPIENTS = "/api/1.0/opensharing/provider/recipients";

  private static TestServer server;
  private static String activations;

  @BeforeAll
  static void startServer() {
    server = TestServer.start();
    activations =
        "http://127.0.0.1:" + server.server().port() + "/api/1.0/opensharing/activations/";
  }

  @AfterAll
  static void stopServer() {
    server.close();
  }

  @Test
  void requiresACatalogAuthorizedPrincipal() {
    // Missing bearer token is unauthenticated.
    Response missing = server.get(RECIPIENTS, null);
    assertEquals(401, missing.status());
    assertEquals(ErrorCodes.UNAUTHENTICATED, missing.errorCode());
  }

  @Test
  void createsListsUpdatesAndDeletesARecipient() {
    // Owner creates a TOKEN recipient and gets an activation URL.
    Response created =
        server.post(
            RECIPIENTS,
            "alice-token",
            "{\"name\":\"Acme\",\"comment\":\"partner\",\"authenticationType\":\"TOKEN\"}");
    assertEquals(201, created.status());
    assertEquals("acme", created.body().path("name").asText());
    assertEquals("TOKEN", created.body().path("authenticationType").asText());
    assertTrue(created.body().path("activationUrl").asText().startsWith(activations));
    assertFalse(created.body().path("id").isMissingNode());

    // Owner lists recipients including activation URLs.
    Response list = server.get(RECIPIENTS, "alice-token");
    assertEquals(200, list.status());
    assertEquals("acme", list.body().path("items").get(0).path("name").asText());
    assertTrue(
        list.body().path("items").get(0).path("activationUrl").asText().startsWith(activations));

    // Get by name is case-insensitive and allowed for any catalog principal.
    Response fetched = server.get(RECIPIENTS + "/ACME", "bob-token");
    assertEquals(200, fetched.status());
    assertEquals("acme", fetched.body().path("name").asText());
    assertEquals("partner", fetched.body().path("comment").asText());
    assertEquals("TOKEN", fetched.body().path("authenticationType").asText());
    assertTrue(fetched.body().path("activationUrl").asText().startsWith(activations));

    // Non-owner cannot update.
    Response stolen = server.patch(RECIPIENTS + "/acme", "bob-token", "{\"comment\":\"stolen\"}");
    assertEquals(403, stolen.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, stolen.errorCode());

    // Owner updates the comment.
    Response updated =
        server.patch(RECIPIENTS + "/acme", "alice-token", "{\"comment\":\"updated\"}");
    assertEquals(200, updated.status());
    assertEquals("updated", updated.body().path("comment").asText());

    // Owner deletes the recipient.
    assertEquals(204, server.delete(RECIPIENTS + "/acme", "alice-token").status());

    // Deleted recipient is 404.
    Response deleted = server.get(RECIPIENTS + "/acme", "alice-token");
    assertEquals(404, deleted.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, deleted.errorCode());
  }
}
