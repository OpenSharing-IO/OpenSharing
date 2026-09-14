package io.opensharing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * The published OpenAPI documents, which are derived from the controllers rather than written. What
 * these tests are about is what a client generator would find in them: two APIs that stay apart,
 * each described in the spelling it is served in.
 */
class OpenApiDocumentTest extends ServerTestBase {

  private static final String TABLES = "/shares/{share}/schemas/{schema}/tables";

  private JsonNode document(String group) throws Exception {
    String body =
        mvc.perform(get("/v3/api-docs/" + group)).andReturn().getResponse().getContentAsString();
    return json.readTree(body);
  }

  @Test
  void describesTheProtocolARecipientCalls() throws Exception {
    JsonNode paths = document("protocol").get("paths");

    assertNotNull(
        paths.get(PROTOCOL_BASE + TABLES),
        "table discovery is part of the protocol a recipient generates a client for");
    assertNotNull(paths.get(PROTOCOL_BASE + TABLES + "/{table}/query"));
    assertNotNull(paths.get(PROTOCOL_BASE + "/iceberg/v1/config"));
    assertNotNull(
        paths.get(PROTOCOL_BASE + "/iceberg/v1/shares/{share}/namespaces/{namespace}/tables/{table}"),
        "the Iceberg catalog is part of the protocol too");
  }

  @Test
  void keepsTheTwoApisApart() throws Exception {
    JsonNode protocol = document("protocol").get("paths");
    JsonNode admin = document("admin").get("paths");

    assertNotNull(admin.get(ADMIN_BASE + "/shares"));
    assertFalse(
        protocol.has(ADMIN_BASE + "/shares"),
        "a recipient has no business seeing the provider's admin API");
    assertFalse(admin.has(PROTOCOL_BASE + "/shares"));
  }

  @Test
  void spellsEachApiTheWayItIsServed() throws Exception {
    JsonNode adminShare =
        document("admin").get("components").get("schemas").get("ShareResponse").get("properties");

    assertTrue(adminShare.has("share_id"), "the admin API speaks snake_case, and its schema too");
    assertFalse(adminShare.has("shareId"));

    JsonNode table =
        document("protocol").get("components").get("schemas").get("Table").get("properties");
    assertTrue(table.has("shareId"), "the protocol is camelCase and fixed by the spec");
  }

  /** The caller comes from the bearer token, so a client must not be asked to pass one. */
  @Test
  void keepsTheAuthenticatedCallerOutOfTheRequest() throws Exception {
    JsonNode protocol = document("protocol");
    JsonNode listShares = protocol.get("paths").get(PROTOCOL_BASE + "/shares").get("get");

    for (JsonNode parameter : listShares.get("parameters")) {
      assertFalse(
          "principal".equals(parameter.get("name").asText()),
          "the recipient is resolved from the token, not sent as a parameter");
    }
    assertFalse(protocol.get("components").get("schemas").has("RecipientPrincipal"));
    assertFalse(document("admin").get("components").get("schemas").has("Caller"));
  }

  @Test
  void namesTheStreamedResponseOnce() throws Exception {
    JsonNode content =
        document("protocol")
            .get("paths")
            .get(PROTOCOL_BASE + TABLES + "/{table}/query")
            .get("post")
            .get("responses")
            .get("200")
            .get("content");

    assertEquals(1, content.size(), "one media type, spelled one way: " + content.toString());
    assertTrue(content.has("application/x-ndjson;charset=utf-8"));
  }

  @Test
  void saysHowARequestIsAuthenticated() throws Exception {
    JsonNode schemes = document("protocol").get("components").get("securitySchemes");

    assertEquals("bearer", schemes.get("recipientToken").get("scheme").asText());
    assertEquals(
        "recipientToken",
        document("protocol").get("security").get(0).fieldNames().next(),
        "every protocol call needs a recipient's token");
  }
}
