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

class ProtocolTablesTest extends ProtocolApiSupport {

  @Test
  void listsGrantedTablesInASchema() {
    createShare("table-share");
    addObject("table-share", "TABLE", "main.sales.orders", "sales.orders");
    addObject("table-share", "TABLE", "main.sales.customers", "sales.customers");
    addObject("table-share", "TABLE", "main.ops.people", "ops.people");
    String bearer = createAndActivateRecipient("table-partner");
    grant("table-share", "table-partner");

    // Share and schema lookups are case-insensitive.
    Response list = server.get(PROTOCOL + "/shares/TABLE-SHARE/schemas/SALES/tables", bearer);
    assertEquals(200, list.status());
    assertJsonUtf8(list);
    JsonNode items = list.body().path("items");
    assertEquals(2, items.size());
    JsonNode customers = items.path(0);
    assertEquals("customers", customers.path("name").asText());
    assertEquals("sales", customers.path("schema").asText());
    assertEquals("table-share", customers.path("share").asText());
    assertTrue(customers.has("shareId"));
    assertEquals("main.sales.customers", customers.path("id").asText());
    assertEquals("s3://test/main.sales.customers/", customers.path("location").asText());
    assertEquals("url", customers.path("accessModes").path(0).asText());
    assertEquals("dir", customers.path("accessModes").path(1).asText());
    assertEquals("orders", items.path(1).path("name").asText());
    assertTrue(list.body().path("nextPageToken").isMissingNode());

    Response missing = server.get(PROTOCOL + "/shares/table-share/schemas/missing/tables", bearer);
    assertEquals(404, missing.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, missing.errorCode());
  }

  @Test
  void listsCatalogChildrenOfASharedSchema() {
    createShare("schema-tables");
    addObject("schema-tables", "SCHEMA", "main.hr", "hr");
    createShare("hidden-tables");
    addObject("hidden-tables", "TABLE", "main.sales.orders", "sales.orders");
    String bearer = createAndActivateRecipient("schema-tables-partner");
    grant("schema-tables", "schema-tables-partner");

    Response list = server.get(PROTOCOL + "/shares/schema-tables/schemas/hr/tables", bearer);
    assertEquals(200, list.status());
    JsonNode items = list.body().path("items");
    assertEquals(2, items.size());
    assertEquals("employees", items.path(0).path("name").asText());
    assertEquals("s3://test/main.hr.employees/", items.path(0).path("location").asText());
    assertEquals("main.hr.employees", items.path(0).path("id").asText());
    JsonNode salaries = items.path(1);
    assertEquals("salaries", salaries.path("name").asText());
    assertEquals("s3://test/main.hr.salaries/", salaries.path("location").asText());
    assertEquals("main.hr.salaries", salaries.path("id").asText());
    assertEquals("url", salaries.path("accessModes").path(0).asText());
    assertEquals("dir", salaries.path("accessModes").path(1).asText());

    // An ungranted share is reported as missing.
    Response hidden = server.get(PROTOCOL + "/shares/hidden-tables/schemas/sales/tables", bearer);
    assertEquals(404, hidden.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, hidden.errorCode());
  }

  @Test
  void namesCatalogChildrenOfASharedSchemaByLowercaseAlias() {
    createShare("cased-tables");
    addObject("cased-tables", "SCHEMA", "main.legal", "legal");
    String bearer = createAndActivateRecipient("cased-tables-partner");
    grant("cased-tables", "cased-tables-partner");

    Response list = server.get(PROTOCOL + "/shares/cased-tables/schemas/legal/tables", bearer);
    assertEquals(200, list.status());
    JsonNode items = list.body().path("items");
    assertEquals(1, items.size());
    assertEquals("contracts", items.path(0).path("name").asText());
    assertEquals("legal", items.path(0).path("schema").asText());
  }

  @Test
  void givesEveryDeltaTableUrlAndDirAccess() {
    createShare("subtype-tables");
    addObject("subtype-tables", "TABLE", "main.finance.ledger", "finance.ledger");
    String bearer = createAndActivateRecipient("subtype-tables-partner");
    grant("subtype-tables", "subtype-tables-partner");

    // The catalog reports no subtype for this table.
    Response list = server.get(PROTOCOL + "/shares/subtype-tables/schemas/finance/tables", bearer);
    assertEquals(200, list.status());
    JsonNode accessModes = list.body().path("items").path(0).path("accessModes");
    assertEquals(2, accessModes.size());
    assertEquals("url", accessModes.path(0).asText());
    assertEquals("dir", accessModes.path(1).asText());
  }

  @Test
  void paginatesTablesInASchema() {
    createShare("paged-tables");
    addObject("paged-tables", "TABLE", "main.sales.table1", "sales.a");
    addObject("paged-tables", "TABLE", "main.sales.orders", "sales.b");
    String bearer = createAndActivateRecipient("table-paged");
    grant("paged-tables", "table-paged");

    String tables = PROTOCOL + "/shares/paged-tables/schemas/sales/tables";
    Response first = server.get(page(tables, 1, null), bearer);
    assertEquals(200, first.status());
    assertEquals(1, first.body().path("items").size());
    assertEquals("a", first.body().path("items").path(0).path("name").asText());
    String pageToken = first.body().path("nextPageToken").asText();
    assertFalse(pageToken.isEmpty());
    Response second = server.get(page(tables, 1, pageToken), bearer);
    assertEquals(200, second.status());
    assertEquals(1, second.body().path("items").size());
    assertEquals("b", second.body().path("items").path(0).path("name").asText());
    assertTrue(second.body().path("nextPageToken").isMissingNode());

    // A page size of 0 returns no tables, but a token to resume from.
    Response zero = server.get(page(tables, 0, null), bearer);
    assertEquals(0, zero.body().path("items").size());
    assertFalse(zero.body().path("nextPageToken").isMissingNode());
  }

  @Test
  void listsAllTablesInAGrantedShare() {
    createShare("all-tables");
    addObject("all-tables", "TABLE", "main.sales.orders", "sales.orders");
    addObject("all-tables", "SCHEMA", "main.hr", "hr");
    createShare("empty-tables");
    String bearer = createAndActivateRecipient("all-tables-partner");
    grant("all-tables", "all-tables-partner");
    grant("empty-tables", "all-tables-partner");

    // Catalog children of a shared schema and included tables, by schema and then name.
    String all = PROTOCOL + "/shares/all-tables/all-tables";
    Response list = server.get(PROTOCOL + "/shares/ALL-TABLES/all-tables", bearer);
    assertEquals(200, list.status());
    assertJsonUtf8(list);
    JsonNode items = list.body().path("items");
    assertEquals(3, items.size());
    assertEquals("hr", items.path(0).path("schema").asText());
    assertEquals("employees", items.path(0).path("name").asText());
    assertEquals("hr", items.path(1).path("schema").asText());
    assertEquals("salaries", items.path(1).path("name").asText());
    assertEquals("sales", items.path(2).path("schema").asText());
    assertEquals("orders", items.path(2).path("name").asText());
    assertTrue(list.body().path("nextPageToken").isMissingNode());

    Response first = server.get(page(all, 1, null), bearer);
    assertEquals(200, first.status());
    assertEquals(1, first.body().path("items").size());
    assertEquals("employees", first.body().path("items").path(0).path("name").asText());
    String pageToken = first.body().path("nextPageToken").asText();
    assertFalse(pageToken.isEmpty());
    Response second = server.get(page(all, 2, pageToken), bearer);
    assertEquals(200, second.status());
    JsonNode rest = second.body().path("items");
    assertEquals(2, rest.size());
    assertEquals("salaries", rest.path(0).path("name").asText());
    assertEquals("orders", rest.path(1).path("name").asText());
    assertTrue(second.body().path("nextPageToken").isMissingNode());

    Response empty = server.get(PROTOCOL + "/shares/empty-tables/all-tables", bearer);
    assertEquals(200, empty.status());
    assertEquals(0, empty.body().path("items").size());
    assertTrue(empty.body().path("nextPageToken").isMissingNode());
    Response missing = server.get(PROTOCOL + "/shares/missing/all-tables", bearer);
    assertEquals(404, missing.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, missing.errorCode());
  }

  /** The protocol serves its bodies as {@code application/json} in UTF-8. */
  private static void assertJsonUtf8(Response response) {
    MediaType contentType = response.headers().contentType();
    assertTrue(contentType.is(MediaType.JSON), contentType.toString());
    assertEquals(StandardCharsets.UTF_8, contentType.charset());
  }
}
