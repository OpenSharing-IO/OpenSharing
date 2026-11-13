package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import org.junit.jupiter.api.Test;

/** A table shared from a start version refuses reads of the versions before it. */
class ProtocolTableStartVersionTest extends LocalDeltaTableSupport {

  @Test
  void refusesVersionsBeforeTheStartVersion() {
    createShare("start-version");
    addObject("start-version", "TABLE", "main.hr.salaries", "hr.salaries", 2);
    String bearer = createAndActivateRecipient("start-version-partner");
    grant("start-version", "start-version-partner");
    String table = PROTOCOL + "/shares/start-version/schemas/hr/tables/salaries";

    // The latest version is at or after the start version.
    assertEquals(200, server.get(table + "/metadata", bearer).status());
    assertEquals(200, server.get(table + "/metadata?version=2", bearer).status());

    for (String before :
        new String[] {
          table + "/metadata?version=1",
          table + "/metadata?timestamp=2024-01-01T01:30:00Z",
          table + "/changes?startingVersion=0"
        }) {
      Response refused = server.get(before, bearer);
      assertEquals(403, refused.status(), before);
      assertEquals(ErrorCodes.PERMISSION_DENIED, refused.errorCode(), before);
      assertTrue(
          refused.text().contains("You can only query table data since version 2."),
          refused.text());
    }
    Response query = server.post(table + "/query", bearer, "{\"version\":0}");
    assertEquals(403, query.status(), query.text());
    Response range = server.post(table + "/query", bearer, "{\"startingVersion\":1}");
    assertEquals(403, range.status(), range.text());
  }
}
