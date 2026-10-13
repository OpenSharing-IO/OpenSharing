package io.opensharing.share;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opensharing.TestServer;
import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SharePermissionAdminServiceTest {

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
  void grantsAndRevokesSelect() {
    // Create the share that will be granted.
    assertEquals(201, server.post(SHARES, "alice-token", "{\"name\":\"sales\"}").status());

    // Create the recipient that will receive SELECT.
    String recipient =
        "{\"name\":\"Acme\",\"authenticationType\":\"TOKEN\",\"tokenExpirationDays\":30}";
    assertEquals(201, server.post(RECIPIENTS, "alice-token", recipient).status());

    // Non-owner cannot grant.
    Response denied =
        permissions(
            "sales",
            "bob-token",
            "{\"changes\":[{\"recipientName\":\"acme\",\"add\":[\"SELECT\"]}]}");
    assertEquals(403, denied.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, denied.errorCode());

    // Owner grants SELECT (recipient name is case-insensitive).
    Response granted = change("sales", "Acme", "add");
    assertEquals(200, granted.status());
    assertEquals(1, granted.body().path("items").size());
    assertEquals("acme", granted.body().path("items").get(0).path("recipientName").asText());
    assertEquals("sales", granted.body().path("items").get(0).path("shareName").asText());
    assertEquals("SELECT", granted.body().path("items").get(0).path("privilege").asText());

    // Granting the same privilege again is idempotent.
    Response again = change("sales", "acme", "add");
    assertEquals(200, again.status());
    assertEquals(1, again.body().path("items").size());

    // Listing permissions is allowed for any catalog principal.
    Response listed = server.get(SHARES + "/sales/permissions", "bob-token");
    assertEquals(200, listed.status());
    assertEquals("SELECT", listed.body().path("items").get(0).path("privilege").asText());

    // Owner revokes SELECT.
    Response revoked = change("sales", "acme", "remove");
    assertEquals(200, revoked.status());
    assertEquals(0, revoked.body().path("items").size());

    // Revoking a privilege that is not granted is 404.
    Response missing = change("sales", "acme", "remove");
    assertEquals(404, missing.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, missing.errorCode());
  }

  @Test
  void rejectsNullChangesAndPrivileges() {
    assertEquals(201, server.post(SHARES, "alice-token", "{\"name\":\"guarded\"}").status());

    // A null change is rejected.
    Response nullChange = permissions("guarded", "alice-token", "{\"changes\":[null]}");
    assertEquals(400, nullChange.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, nullChange.errorCode());
    assertEquals("changes must not contain null", nullChange.body().path("message").asText());

    // A null privilege is rejected in add and in remove.
    for (String list : new String[] {"add", "remove"}) {
      Response nullPrivilege =
          permissions(
              "guarded",
              "alice-token",
              "{\"changes\":[{\"recipientName\":\"acme\",\"" + list + "\":[null]}]}");
      assertEquals(400, nullPrivilege.status());
      assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, nullPrivilege.errorCode());
      assertEquals(
          "changes.add and changes.remove must not contain null",
          nullPrivilege.body().path("message").asText());
    }
  }

  private static Response permissions(String share, String token, String body) {
    return server.patch(SHARES + "/" + share + "/permissions", token, body);
  }

  // Adds or removes SELECT for one recipient as the owner.
  private static Response change(String share, String recipient, String list) {
    return permissions(
        share,
        "alice-token",
        "{\"changes\":[{\"recipientName\":\"" + recipient + "\",\"" + list + "\":[\"SELECT\"]}]}");
  }
}
