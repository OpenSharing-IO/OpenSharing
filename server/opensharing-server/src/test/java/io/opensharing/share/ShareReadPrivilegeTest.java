package io.opensharing.share;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opensharing.TestServer;
import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** In the local catalog, alice and bob hold USE_SHARE and carol does not. */
class ShareReadPrivilegeTest {

  private static final String SHARES = "/api/1.0/opensharing/provider/shares";

  private static TestServer server;

  @BeforeAll
  static void startServer() {
    server = TestServer.start();
    assertEquals(201, server.post(SHARES, "alice-token", "{\"name\":\"alice_share\"}").status());
    assertEquals(201, server.post(SHARES, "carol-token", "{\"name\":\"carol_share\"}").status());
  }

  @AfterAll
  static void stopServer() {
    server.close();
  }

  @Test
  void listsOnlyOwnSharesWithoutUseShare() {
    assertEquals(List.of("carol_share"), names(server.get(SHARES, "carol-token")));
    assertEquals(List.of("alice_share", "carol_share"), names(server.get(SHARES, "bob-token")));
  }

  @Test
  void readsOthersSharesOnlyWithUseShare() {
    assertEquals(200, server.get(SHARES + "/carol_share", "carol-token").status());
    assertEquals(200, server.get(SHARES + "/carol_share/permissions", "carol-token").status());
    assertEquals(200, server.get(SHARES + "/carol_share", "bob-token").status());
    assertEquals(200, server.get(SHARES + "/carol_share/permissions", "bob-token").status());

    Response share = server.get(SHARES + "/alice_share?include_shared_data=true", "carol-token");
    assertEquals(403, share.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, share.errorCode());
    Response permissions = server.get(SHARES + "/alice_share/permissions", "carol-token");
    assertEquals(403, permissions.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, permissions.errorCode());
  }

  private static List<String> names(Response response) {
    assertEquals(200, response.status());
    List<String> names = new ArrayList<>();
    response.body().path("items").forEach(item -> names.add(item.path("name").asText()));
    return names;
  }
}
