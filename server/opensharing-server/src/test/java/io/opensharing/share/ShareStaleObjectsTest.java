package io.opensharing.share;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import io.opensharing.protocol.ProtocolApiSupport;
import org.junit.jupiter.api.Test;

class ShareStaleObjectsTest extends ProtocolApiSupport {

  @Test
  void asksTheProviderToReshareATableTheCatalogReplacedOrDropped() {
    createShare("stale-replaced");
    addObject("stale-replaced", "TABLE", "main.sales.orders", "sales.orders");
    createShare("stale-dropped");
    addObject("stale-dropped", "TABLE", "main.sales.orders", "sales.orders");
    String replacedShare = PROVIDER + "/shares/stale-replaced?include_shared_data=true";
    assertEquals(200, server.get(replacedShare, ALICE).status());

    // Another table now has the shared table's name.
    editIncluded("stale-replaced", "main.sales.orders", o -> o.setSourceAssetId("other-id"));
    Response replaced = server.get(replacedShare, ALICE);
    assertEquals(409, replaced.status());
    assertEquals(ErrorCodes.RESOURCE_CONFLICT, replaced.errorCode());
    assertEquals(
        "table 'sales.orders' in share 'stale-replaced' was dropped or replaced in the catalog;"
            + " remove it from the share and add it again",
        replaced.body().path("message").asText());
    // The share itself is still readable without its objects.
    assertEquals(200, server.get(PROVIDER + "/shares/stale-replaced", ALICE).status());

    // The shared table is gone from the catalog.
    editIncluded("stale-dropped", "main.sales.orders", o -> o.setFullName("main.sales.dropped"));
    Response dropped =
        server.get(PROVIDER + "/shares/stale-dropped?include_shared_data=true", ALICE);
    assertEquals(409, dropped.status());
    assertEquals(ErrorCodes.RESOURCE_CONFLICT, dropped.errorCode());

    // Sharing the table again picks up the catalog's current table.
    String remove =
        "{\"updates\":[{\"action\":\"REMOVE\","
            + "\"dataObject\":{\"type\":\"TABLE\",\"sharedAs\":\"sales.orders\"}}]}";
    assertEquals(
        200, server.patch(PROVIDER + "/shares/stale-replaced", ALICE, remove).status());
    addObject("stale-replaced", "TABLE", "main.sales.orders", "sales.orders");
    assertEquals(200, server.get(replacedShare, ALICE).status());
  }

  @Test
  void asksTheProviderToReshareASchemaTheCatalogReplaced() {
    createShare("stale-schema");
    addObject("stale-schema", "SCHEMA", "main.hr", "hr");
    String share = PROVIDER + "/shares/stale-schema?include_shared_data=true";
    assertEquals(200, server.get(share, ALICE).status());

    editIncluded("stale-schema", "main.hr", o -> o.setSourceAssetId("other-id"));
    Response replaced = server.get(share, ALICE);
    assertEquals(409, replaced.status());
    assertEquals(ErrorCodes.RESOURCE_CONFLICT, replaced.errorCode());
    assertEquals(
        "schema 'hr' in share 'stale-schema' was dropped or replaced in the catalog;"
            + " remove it from the share and add it again",
        replaced.body().path("message").asText());
  }
}
