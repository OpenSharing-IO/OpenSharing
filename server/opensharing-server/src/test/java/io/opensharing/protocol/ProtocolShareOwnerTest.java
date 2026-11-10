package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.TestServer.Response;
import org.junit.jupiter.api.Test;

/** The owner of a share is the provider's business: recipients never see it. */
class ProtocolShareOwnerTest extends ProtocolApiSupport {

  @Test
  void onlyTheProviderApiReportsTheOwner() {
    createShare("owned-share");
    String bearer = createAndActivateRecipient("owner-partner");
    grant("owned-share", "owner-partner");

    Response provider = server.get(PROVIDER + "/shares/owned-share", ALICE);
    assertEquals(200, provider.status());
    assertEquals("catalog-alice-id", provider.body().path("ownerId").asText());

    Response listed = server.get(PROTOCOL + "/shares", bearer);
    assertEquals(200, listed.status());
    assertEquals(1, listed.body().path("items").size());
    assertEquals("owned-share", listed.body().path("items").path(0).path("name").asText());
    assertTrue(listed.body().path("items").path(0).path("ownerId").isMissingNode());

    Response got = server.get(PROTOCOL + "/shares/owned-share", bearer);
    assertEquals(200, got.status());
    assertEquals("owned-share", got.body().path("share").path("name").asText());
    assertTrue(got.body().path("share").path("ownerId").isMissingNode());
  }
}
