package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.MediaType;
import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OpenSharingProtocolServiceTest extends ProtocolApiSupport {

  private static final String SHARES = PROTOCOL + "/shares";

  @Test
  void authenticatesARecipientAndListsOnlyGrantedShares() {
    // Create two shares and grant only one to the recipient.
    createShare("protocol-granted", "Visible");
    createShare("protocol-hidden", "Hidden");
    String bearer = createAndActivateRecipient("protocol-partner");
    grant("protocol-granted", "protocol-partner");

    // The protocol list exposes the granted share and its protocol metadata only.
    Response list = server.get(SHARES, bearer);
    assertEquals(200, list.status());
    assertJsonUtf8(list);
    assertEquals(1, list.body().path("items").size());
    JsonNode listed = list.body().path("items").path(0);
    assertEquals("protocol-granted", listed.path("name").asText());
    assertEquals("Visible", listed.path("displayName").asText());
    assertTrue(listed.has("id"));
    assertTrue(listed.path("objects").isMissingNode());
    assertTrue(list.body().path("nextPageToken").isMissingNode());

    // Get wraps the granted share; lookup is case-insensitive.
    Response get = server.get(SHARES + "/PROTOCOL-GRANTED", bearer);
    assertEquals(200, get.status());
    assertJsonUtf8(get);
    JsonNode share = get.body().path("share");
    assertEquals("protocol-granted", share.path("name").asText());
    assertEquals("Visible", share.path("displayName").asText());
    assertTrue(share.has("id"));
    assertTrue(share.path("objects").isMissingNode());

    // An ungranted share is reported as missing rather than forbidden.
    Response hidden = server.get(SHARES + "/protocol-hidden", bearer);
    assertEquals(404, hidden.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, hidden.errorCode());
  }

  @Test
  void rejectsMissingInvalidAndSupersededTokens() {
    // Protocol requests require an activated recipient bearer.
    Response missing = server.get(SHARES, null);
    assertEquals(401, missing.status());
    assertEquals("Bearer", missing.headers().get("WWW-Authenticate"));
    assertEquals(ErrorCodes.UNAUTHENTICATED, missing.errorCode());
    Response invalid = server.get(SHARES, "invalid");
    assertEquals(401, invalid.status());
    assertEquals(ErrorCodes.UNAUTHENTICATED, invalid.errorCode());

    // Rotation with the default zero grace period invalidates the old bearer immediately.
    String bearer = createAndActivateRecipient("protocol-rotated");
    Response rotated =
        server.post(
            PROVIDER + "/recipients/protocol-rotated/rotate-token",
            ALICE,
            "{\"tokenExpirationDays\":1}");
    assertEquals(201, rotated.status());
    Response superseded = server.get(SHARES, bearer);
    assertEquals(401, superseded.status());
    assertEquals(ErrorCodes.UNAUTHENTICATED, superseded.errorCode());
  }

  @Test
  void paginatesGrantedSharesWithOpaqueTokens() {
    // Grant two ordered shares to one recipient.
    createShare("protocol-page-a", "A");
    createShare("protocol-page-b", "B");
    String bearer = createAndActivateRecipient("protocol-paged");
    grant("protocol-page-a", "protocol-paged");
    grant("protocol-page-b", "protocol-paged");

    // Resume from the opaque token without repeating the first item.
    Response first = server.get(page(SHARES, 1, null), bearer);
    assertEquals(200, first.status());
    assertEquals(1, first.body().path("items").size());
    String pageToken = first.body().path("nextPageToken").asText();
    assertFalse(pageToken.isEmpty());
    Response second = server.get(page(SHARES, 1, pageToken), bearer);
    assertEquals(200, second.status());
    assertEquals(1, second.body().path("items").size());
    assertTrue(second.body().path("nextPageToken").isMissingNode());
    assertNotEquals(
        first.body().path("items").path(0).path("name").asText(),
        second.body().path("items").path(0).path("name").asText());

    // A page size of 0 returns no items, with a token that resumes from the same position.
    Response empty = server.get(page(SHARES, 0, null), bearer);
    assertEquals(200, empty.status());
    assertEquals(0, empty.body().path("items").size());
    String resume = empty.body().path("nextPageToken").asText();
    assertEquals(2, server.get(page(SHARES, 2, resume), bearer).body().path("items").size());

    // Malformed pagination input is rejected.
    Response malformed = server.get(SHARES + "?pageToken=not-a-token", bearer);
    assertEquals(400, malformed.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, malformed.errorCode());
  }

  @Test
  void listsManyGrantedSharesAcrossPages() {
    int total = 25;
    String bearer = createAndActivateRecipient("protocol-many");
    for (int i = 0; i < total; i++) {
      String name = "protocol-many-%02d".formatted(i);
      createShare(name, "Share " + i);
      grant(name, "protocol-many");
    }

    Response first = server.get(page(SHARES, 10, null), bearer);
    assertNames(first, 10, "protocol-many-00", "protocol-many-09");
    Response second =
        server.get(page(SHARES, 10, first.body().path("nextPageToken").asText()), bearer);
    assertNames(second, 10, "protocol-many-10", "protocol-many-19");
    Response last =
        server.get(page(SHARES, 10, second.body().path("nextPageToken").asText()), bearer);
    assertNames(last, 5, "protocol-many-20", "protocol-many-24");
    assertTrue(last.body().path("nextPageToken").isMissingNode());

    // A page size above the maximum is capped rather than rejected.
    Response all = server.get(page(SHARES, 10000, null), bearer);
    assertNames(all, total, "protocol-many-00", "protocol-many-24");
    assertTrue(all.body().path("nextPageToken").isMissingNode());
  }

  private static void assertJsonUtf8(Response response) {
    MediaType contentType = response.headers().contentType();
    assertTrue(contentType.is(MediaType.JSON), contentType.toString());
    assertEquals(StandardCharsets.UTF_8, contentType.charset());
  }

  private static void assertNames(Response response, int size, String first, String last) {
    assertEquals(200, response.status());
    JsonNode items = response.body().path("items");
    assertEquals(size, items.size());
    assertEquals(first, items.path(0).path("name").asText());
    assertEquals(last, items.path(size - 1).path("name").asText());
  }
}
