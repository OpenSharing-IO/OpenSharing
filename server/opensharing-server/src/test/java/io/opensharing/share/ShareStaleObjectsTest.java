package io.opensharing.share;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import io.opensharing.protocol.ProtocolApiSupport;
import org.junit.jupiter.api.Test;

class ShareStaleObjectsTest extends ProtocolApiSupport {

  @Test
  void getShareAsksToRemoveAReplacedOrDroppedTable() {
    createShare("replaced-table");
    addObject("replaced-table", "TABLE", "main.sales.orders", "sales.orders");
    createShare("dropped-table");
    addObject("dropped-table", "TABLE", "main.sales.orders", "sales.orders");
    String replacedSharePath = PROVIDER + "/shares/replaced-table?include_shared_data=true";
    assertEquals(200, server.get(replacedSharePath, ALICE).status());

    // Another table now has the shared table's name.
    replaceInCatalog("replaced-table", "main.sales.orders");
    Response replacedShare = server.get(replacedSharePath, ALICE);
    assertEquals(409, replacedShare.status());
    assertEquals(ErrorCodes.RESOURCE_CONFLICT, replacedShare.errorCode());
    assertEquals(
        "table 'sales.orders' in share 'replaced-table' was dropped or replaced in the catalog;"
            + " remove it from the share",
        replacedShare.body().path("message").asText());
    // The share itself is still readable without its objects.
    assertEquals(200, server.get(PROVIDER + "/shares/replaced-table", ALICE).status());

    // The shared table is gone from the catalog.
    dropFromCatalog("dropped-table", "main.sales.orders");
    Response droppedShare =
        server.get(PROVIDER + "/shares/dropped-table?include_shared_data=true", ALICE);
    assertEquals(409, droppedShare.status());
    assertEquals(ErrorCodes.RESOURCE_CONFLICT, droppedShare.errorCode());

    // Once the provider removes the table, the share reads again with its objects.
    String removeOrders =
        "{\"updates\":[{\"action\":\"REMOVE\","
            + "\"dataObject\":{\"type\":\"TABLE\",\"sharedAs\":\"sales.orders\"}}]}";
    assertEquals(
        200, server.patch(PROVIDER + "/shares/replaced-table", ALICE, removeOrders).status());
    assertEquals(200, server.get(replacedSharePath, ALICE).status());
  }
}
