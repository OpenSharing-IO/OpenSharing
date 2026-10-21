package io.opensharing.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opensharing.TestServer;
import io.opensharing.TestServer.Response;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProviderMaxResultsTest {

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
  void rejectsAMaxResultsThatIsNotAnInteger() {
    assertEquals(201, server.post(SHARES, "alice-token", "{\"name\":\"max-results\"}").status());
    for (String list : List.of(SHARES, RECIPIENTS, SHARES + "/max-results/permissions")) {
      for (String value : List.of("ten", "1.5", "3000000000")) {
        Response invalid = server.get(list + "?maxResults=" + value, "alice-token");
        assertEquals(400, invalid.status(), list);
        assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, invalid.errorCode());
        assertEquals("maxResults must be an integer", invalid.body().path("message").asText());
      }
    }
  }

  @Test
  void treatsAnEmptyMaxResultsAsUnset() {
    for (String list : List.of(SHARES, RECIPIENTS)) {
      Response unset = server.get(list + "?maxResults=", "alice-token");
      assertEquals(200, unset.status(), list);
      assertEquals(server.get(list, "alice-token").body(), unset.body());
    }
  }
}
