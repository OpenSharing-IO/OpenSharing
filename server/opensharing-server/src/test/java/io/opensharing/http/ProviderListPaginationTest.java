package io.opensharing.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.opensharing.TestServer.Response;
import io.opensharing.TestServer;
import io.opensharing.util.ErrorCodes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProviderListPaginationTest {

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
  void pagesSharesByName() {
    for (String name : List.of("paged-c", "paged-a", "paged-b")) {
      create(SHARES, "{\"name\":\"" + name + "\"}");
    }
    List<String> names = walk(SHARES, "name");
    assertTrue(names.containsAll(List.of("paged-a", "paged-b", "paged-c")), names.toString());
  }

  @Test
  void pagesRecipientsByName() {
    for (String name : List.of("partner-c", "partner-a", "partner-b")) {
      create(RECIPIENTS, recipient(name));
    }
    List<String> names = walk(RECIPIENTS, "name");
    assertTrue(names.containsAll(List.of("partner-a", "partner-b", "partner-c")), names.toString());
  }

  @Test
  void pagesSharePermissionsByRecipientName() {
    create(SHARES, "{\"name\":\"granted\"}");
    for (String name : List.of("grantee-c", "grantee-a", "grantee-b")) {
      create(RECIPIENTS, recipient(name));
    }

    // Updating returns the first page of the share's permissions, which holds all three.
    Response updated =
        server.patch(
            SHARES + "/granted/permissions",
            "alice-token",
            """
            {"changes":[
              {"recipientName":"grantee-c","add":["SELECT"]},
              {"recipientName":"grantee-a","add":["SELECT"]},
              {"recipientName":"grantee-b","add":["SELECT"]}]}
            """);
    assertEquals(200, updated.status());
    assertEquals(3, updated.body().path("items").size());
    assertTrue(updated.body().path("nextPageToken").isMissingNode());

    assertEquals(
        List.of("grantee-a", "grantee-b", "grantee-c"),
        walk(SHARES + "/granted/permissions", "recipientName"));
  }

  @Test
  void resumesFromPageTokens() {
    create(SHARES, "{\"name\":\"tokened\"}");
    for (String name : List.of("holder-a", "holder-b", "holder-c")) {
      create(RECIPIENTS, recipient(name));
      Response granted =
          server.patch(
              SHARES + "/tokened/permissions",
              "alice-token",
              "{\"changes\":[{\"recipientName\":\"" + name + "\",\"add\":[\"SELECT\"]}]}");
      assertEquals(200, granted.status());
    }
    String url = SHARES + "/tokened/permissions";

    JsonNode first = fetch(url + "?maxResults=1");
    assertEquals(List.of("holder-a"), values(first, "recipientName"));
    String token = first.path("nextPageToken").asText();

    JsonNode rest = fetch(url + "?pageToken=" + token);
    assertEquals(List.of("holder-b", "holder-c"), values(rest, "recipientName"));
    assertTrue(rest.path("nextPageToken").isMissingNode());

    // A token marks a position, not a page size, so it can be replayed with another maxResults.
    JsonNode replayed = fetch(url + "?pageToken=" + token + "&maxResults=1");
    assertEquals(List.of("holder-b"), values(replayed, "recipientName"));
    assertFalse(replayed.path("nextPageToken").isMissingNode());

    for (String list : List.of(SHARES, RECIPIENTS, url)) {
      Response invalid = server.get(list + "?pageToken=not-a-token", "alice-token");
      assertEquals(400, invalid.status());
      assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, invalid.errorCode());
    }
  }

  @Test
  void validatesPageSizes() {
    create(SHARES, "{\"name\":\"capped\"}");
    JsonNode unset = fetch(SHARES + "?maxResults=0");
    assertEquals(fetch(SHARES).path("items"), unset.path("items"));
    assertTrue(unset.path("items").size() > 0);

    Response negative = server.get(SHARES + "?maxResults=-1", "alice-token");
    assertEquals(400, negative.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, negative.errorCode());
  }

  /**
   * Follows nextPageToken two items at a time from the first page to the last and returns {@code
   * field} of every item, checking that pages come in ascending order.
   */
  private static List<String> walk(String url, String field) {
    List<String> values = new ArrayList<>();
    String token = null;
    do {
      JsonNode body = fetch(url + "?maxResults=2" + (token == null ? "" : "&pageToken=" + token));
      List<String> page = values(body, field);
      assertTrue(page.size() <= 2, body.toString());
      values.addAll(page);
      JsonNode next = body.path("nextPageToken");
      token = next.isMissingNode() ? null : next.asText();
    } while (token != null);
    assertEquals(values.stream().sorted().distinct().toList(), values);
    return values;
  }

  private static JsonNode fetch(String url) {
    Response response = server.get(url, "alice-token");
    assertEquals(200, response.status(), () -> response.body().toString());
    return response.body();
  }

  private static List<String> values(JsonNode body, String field) {
    return body.path("items").findValuesAsText(field);
  }

  private static void create(String url, String body) {
    assertEquals(201, server.post(url, "alice-token", body).status());
  }

  private static String recipient(String name) {
    return "{\"name\":\"%s\",\"authenticationType\":\"TOKEN\",\"tokenExpirationDays\":30}"
        .formatted(name);
  }
}
