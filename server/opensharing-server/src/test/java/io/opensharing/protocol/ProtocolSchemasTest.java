package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.MediaType;
import io.opensharing.TestServer.Response;
import io.opensharing.util.ErrorCodes;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ProtocolSchemasTest extends ProtocolApiSupport {

  @Test
  void listsDistinctSchemasInAGrantedShare() {
    createShare("schema-share");
    addObject("schema-share", "TABLE", "main.sales.table1", "alpha.t1");
    addObject("schema-share", "TABLE", "main.sales.orders", "alpha.t2");
    addObject("schema-share", "SCHEMA", "main.hr", "beta");
    addObject("schema-share", "TABLE", "main.ops.people", "gamma.t3");
    createShare("hidden-share");
    addObject("hidden-share", "TABLE", "main.sales.table1", "hidden.t");
    String bearer = createAndActivateRecipient("schema-partner");
    grant("schema-share", "schema-partner");

    // Distinct shared-as schemas, ordered by name; the share lookup is case-insensitive.
    Response list = server.get(PROTOCOL + "/shares/SCHEMA-SHARE/schemas", bearer);
    assertEquals(200, list.status());
    MediaType contentType = list.headers().contentType();
    assertTrue(contentType.is(MediaType.JSON), contentType.toString());
    assertEquals(StandardCharsets.UTF_8, contentType.charset());
    JsonNode items = list.body().path("items");
    assertEquals(3, items.size());
    assertEquals("alpha", items.path(0).path("name").asText());
    assertEquals("schema-share", items.path(0).path("share").asText());
    assertEquals("beta", items.path(1).path("name").asText());
    assertEquals("gamma", items.path(2).path("name").asText());
    assertTrue(list.body().path("nextPageToken").isMissingNode());

    // Ungranted and missing shares are both reported as missing.
    Response hidden = server.get(PROTOCOL + "/shares/hidden-share/schemas", bearer);
    assertEquals(404, hidden.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, hidden.errorCode());
    Response missing = server.get(PROTOCOL + "/shares/missing/schemas", bearer);
    assertEquals(404, missing.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, missing.errorCode());
  }

  @Test
  void paginatesSchemasAndAllowsAnEmptyGrantedShare() {
    createShare("paged-schemas");
    addObject("paged-schemas", "TABLE", "main.sales.table1", "alpha.t");
    addObject("paged-schemas", "TABLE", "main.sales.orders", "beta.t");
    createShare("empty-share");
    String bearer = createAndActivateRecipient("schema-paged");
    grant("paged-schemas", "schema-paged");
    grant("empty-share", "schema-paged");

    String schemas = PROTOCOL + "/shares/paged-schemas/schemas";
    Response first = server.get(page(schemas, 1, null), bearer);
    assertEquals(200, first.status());
    assertEquals(1, first.body().path("items").size());
    assertEquals("alpha", first.body().path("items").path(0).path("name").asText());
    String pageToken = first.body().path("nextPageToken").asText();
    assertFalse(pageToken.isEmpty());
    Response second = server.get(page(schemas, 1, pageToken), bearer);
    assertEquals(200, second.status());
    assertEquals(1, second.body().path("items").size());
    assertEquals("beta", second.body().path("items").path(0).path("name").asText());
    assertTrue(second.body().path("nextPageToken").isMissingNode());

    // A page size of 0 returns no schemas, but a token to resume from.
    Response zero = server.get(page(schemas, 0, null), bearer);
    assertEquals(0, zero.body().path("items").size());
    assertFalse(zero.body().path("nextPageToken").isMissingNode());

    // A granted share with no objects lists no schemas.
    Response empty = server.get(PROTOCOL + "/shares/empty-share/schemas", bearer);
    assertEquals(200, empty.status());
    assertEquals(0, empty.body().path("items").size());
    assertTrue(empty.body().path("nextPageToken").isMissingNode());
  }
}
