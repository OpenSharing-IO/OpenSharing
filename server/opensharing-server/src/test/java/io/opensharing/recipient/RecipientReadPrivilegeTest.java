package io.opensharing.recipient;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opensharing.TestServer;
import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** In the local catalog, alice and bob hold USE_RECIPIENT and carol does not. */
class RecipientReadPrivilegeTest {

  private static final String RECIPIENTS = "/api/1.0/opensharing/provider/recipients";

  private static TestServer server;

  @BeforeAll
  static void startServer() {
    server = TestServer.start();
    create("alice-token", "alice_recipient");
    create("carol-token", "carol_recipient");
  }

  @AfterAll
  static void stopServer() {
    server.close();
  }

  @Test
  void listsOnlyOwnRecipientsWithoutUseRecipient() {
    assertEquals(List.of("carol_recipient"), names(server.get(RECIPIENTS, "carol-token")));
    assertEquals(
        List.of("alice_recipient", "carol_recipient"), names(server.get(RECIPIENTS, "bob-token")));
  }

  @Test
  void readsOthersRecipientsOnlyWithUseRecipient() {
    assertEquals(200, server.get(RECIPIENTS + "/carol_recipient", "carol-token").status());
    assertEquals(200, server.get(RECIPIENTS + "/carol_recipient", "bob-token").status());

    Response denied = server.get(RECIPIENTS + "/alice_recipient", "carol-token");
    assertEquals(403, denied.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, denied.errorCode());
  }

  private static void create(String token, String name) {
    Response created =
        server.post(
            RECIPIENTS,
            token,
            "{\"name\":\""
                + name
                + "\",\"authenticationType\":\"TOKEN\",\"tokenExpirationDays\":30}");
    assertEquals(201, created.status());
  }

  private static List<String> names(Response response) {
    assertEquals(200, response.status());
    List<String> names = new ArrayList<>();
    response.body().path("items").forEach(item -> names.add(item.path("name").asText()));
    return names;
  }
}
