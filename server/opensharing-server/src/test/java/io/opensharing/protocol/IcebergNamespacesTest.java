package io.opensharing.protocol;

import static io.opensharing.protocol.IcebergConfigTest.ICEBERG;
import static io.opensharing.protocol.IcebergConfigTest.assertAdvertised;
import static io.opensharing.protocol.IcebergConfigTest.assertIcebergError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.opensharing.TestServer.Response;
import org.junit.jupiter.api.Test;

class IcebergNamespacesTest extends LocalIcebergTableSupport {

  @Test
  void listsTheSharesSchemasAsNamespaces() {
    // Share tables under two schemas, sales and hr.
    createShare("iceberg-ns");
    addObject("iceberg-ns", "TABLE", "main.sales.orders", "sales.orders");
    addObject("iceberg-ns", "TABLE", "main.sales.customers", "sales.customers");
    addObject("iceberg-ns", "TABLE", "main.hr.employees", "hr.employees");
    String bearer = createAndActivateRecipient("iceberg-ns-partner");
    grant("iceberg-ns", "iceberg-ns-partner");
    String namespaces = ICEBERG + "/v1/shares/ICEBERG-NS/namespaces";

    // All the share's schemas come back as one-level namespaces, by name.
    Response all = server.get(namespaces, bearer);
    assertEquals(200, all.status());
    JsonNode listed = all.body().path("namespaces");
    assertEquals(2, listed.size());
    assertEquals("hr", listed.path(0).path(0).asText());
    assertEquals(1, listed.path(0).size());
    assertEquals("sales", listed.path(1).path(0).asText());
    assertTrue(all.body().path("next-page-token").isNull());

    // With pageSize=1 and an empty pageToken, the first page has hr and a token for the next.
    Response first = server.get(namespaces + "?pageSize=1&pageToken=", bearer);
    assertEquals("hr", first.body().path("namespaces").path(0).path(0).asText());
    String token = first.body().path("next-page-token").asText();
    // The token's page has sales, and no further token.
    Response second = server.get(namespaces + "?pageSize=1&pageToken=" + token, bearer);
    assertEquals("sales", second.body().path("namespaces").path(0).path(0).asText());
    assertTrue(second.body().path("next-page-token").isNull());

    // A share's namespaces are one level deep, so listing under a parent is rejected.
    assertIcebergError(
        server.get(namespaces + "?parent=sales", bearer), 400, "BadRequestException");
  }

  /** Without a pageToken a request gets every namespace, whatever its pageSize. */
  @Test
  void returnsEveryNamespaceWhenNoPageTokenIsGiven() {
    // Share tables under two schemas, so a pageSize of 1 would split them.
    createShare("iceberg-ns-all");
    addObject("iceberg-ns-all", "TABLE", "main.sales.orders", "sales.orders");
    addObject("iceberg-ns-all", "TABLE", "main.hr.employees", "hr.employees");
    // Activate a recipient for the share.
    String bearer = createAndActivateRecipient("iceberg-ns-all-partner");
    // Grant access to the share.
    grant("iceberg-ns-all", "iceberg-ns-all-partner");

    // Ask for a page of one namespace without a pageToken.
    Response response =
        server.get(ICEBERG + "/v1/shares/iceberg-ns-all/namespaces?pageSize=1", bearer);

    // Both namespaces come back at once.
    assertEquals(200, response.status());
    assertEquals(2, response.body().path("namespaces").size());
    // The response is the last, so its next-page-token is null rather than missing.
    assertTrue(response.body().path("next-page-token").isNull());
  }

  /** A share the recipient holds no SELECT on has no namespaces it can see. */
  @Test
  void reportsAnUngrantedShareAsNoSuchNamespace() {
    // A share the recipient is not granted.
    createShare("iceberg-ns-hidden");
    addObject("iceberg-ns-hidden", "TABLE", "main.sales.orders", "sales.orders");
    String bearer = createAndActivateRecipient("iceberg-ns-stranger");

    // Listing its namespaces finds none.
    assertIcebergError(
        server.get(ICEBERG + "/v1/shares/iceberg-ns-hidden/namespaces", bearer),
        404,
        "NoSuchNamespaceException");
  }

  @Test
  void configListsListNamespaces() {
    // Grant a recipient SELECT on a share.
    createShare("iceberg-ns-config");
    String bearer = createAndActivateRecipient("iceberg-ns-config-partner");
    grant("iceberg-ns-config", "iceberg-ns-config-partner");

    // getConfig lists listNamespaces.
    JsonNode endpoints =
        server.get(ICEBERG + "/v1/config?warehouse=iceberg-ns-config", bearer)
            .body()
            .path("endpoints");
    assertAdvertised(endpoints, "GET /v1/{prefix}/namespaces");
  }

  @Test
  void rejectsNonPositivePageSizes() {
    // Create a share whose namespaces the recipient may list.
    createShare("iceberg-ns-page-size");
    // Activate its recipient.
    String bearer = createAndActivateRecipient("iceberg-ns-page-size-partner");
    // Grant access to the share.
    grant("iceberg-ns-page-size", "iceberg-ns-page-size-partner");
    // Address the namespace listing endpoint.
    String namespaces = ICEBERG + "/v1/shares/iceberg-ns-page-size/namespaces";

    // A zero-sized page cannot advance the client's pagination.
    assertIcebergError(
        server.get(namespaces + "?pageSize=0", bearer), 400, "BadRequestException");
    // Negative sizes are invalid as well.
    assertIcebergError(
        server.get(namespaces + "?pageSize=-1", bearer), 400, "BadRequestException");
  }
}
