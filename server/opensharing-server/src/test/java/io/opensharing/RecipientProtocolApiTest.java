package io.opensharing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The recipient-facing protocol API, exercised the way a recipient client would. */
class RecipientProtocolApiTest extends ServerTestBase {

  @Autowired private JdbcTemplate jdbc;

  private String share;
  private String recipient;
  private String token;

  @BeforeEach
  void shareTablesWithARecipient() throws Exception {
    share = createShare(unique("acme_share"));
    recipient = unique("partner");
    token = createRecipientWithToken(recipient);
    grant(share, recipient);

    addTable(share, "sales.orders", "main.sales.orders");
    addTable(share, "sales.forecast", "main.sales.forecast");
    addTable(share, "research.trial_results", "main.research.trial_results");
  }

  @Test
  void listsOnlyGrantedShares() throws Exception {
    String otherShare = createShare(unique("other_share"));

    List<String> names = names(protocolGet(token, "/shares"));

    assertTrue(names.contains(share));
    assertFalse(names.contains(otherShare));

    mvc.perform(
            get(PROTOCOL_BASE + "/shares/" + otherShare)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("RESOURCE_DOES_NOT_EXIST"));
  }

  /**
   * The whole round trip of the owner's sealed token: stored when she registered, read back out when a
   * recipient's request re-resolves the table, and the read served as her.
   */
  @Test
  void servesAReadAsTheShareOwner() throws Exception {
    JsonNode table = protocolGet(token, "/shares/" + share + "/schemas/sales/tables");

    assertEquals(List.of("forecast", "orders"), names(table));
    mvc.perform(
            post(PROTOCOL_BASE
                    + "/shares/"
                    + share
                    + "/schemas/sales/tables/orders/temporary-table-credentials")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"operation\":\"READ\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void getsAShareWrappedInAShareField() throws Exception {
    JsonNode response = protocolGet(token, "/shares/" + share);

    assertEquals(share, response.get("share").get("name").asText());
    assertEquals("test share", response.get("share").get("comment").asText());
  }

  @Test
  void answersWithUtf8Json() throws Exception {
    mvc.perform(get(PROTOCOL_BASE + "/shares").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(content().encoding("UTF-8"));
  }

  @Test
  void derivesSchemasFromSharedTables() throws Exception {
    JsonNode schemas = protocolGet(token, "/shares/" + share + "/schemas");

    assertEquals(List.of("research", "sales"), names(schemas));
    assertEquals(share, schemas.get("items").get(0).get("share").asText());
  }

  @Test
  void treatsSchemasThatDifferOnlyInCaseAsOneSchema() throws Exception {
    String mixedCase = createShare(unique("mixed_case_share"));
    grant(mixedCase, recipient);
    addTable(mixedCase, "Sales.orders", "main.sales.orders");
    addTable(mixedCase, "sales.forecast", "main.sales.forecast");

    assertEquals(1, protocolGet(token, "/shares/" + mixedCase + "/schemas").get("items").size());
    assertEquals(
        2,
        protocolGet(token, "/shares/" + mixedCase + "/schemas/SALES/tables").get("items").size());
  }

  @Test
  void listsTablesWithLocationAndAccessModes() throws Exception {
    JsonNode tables = protocolGet(token, "/shares/" + share + "/all-tables");

    assertEquals(3, tables.get("items").size());
    JsonNode orders = itemNamed(tables, "orders");
    assertEquals("sales", orders.get("schema").asText());
    assertEquals(share, orders.get("share").asText());
    assertEquals("s3://acme-lake/sales/orders/", orders.get("location").asText());
    assertEquals("dir", orders.get("accessModes").get(0).asText());
  }

  @Test
  void listsTablesWithinASchemaAndRejectsUnknownSchemas() throws Exception {
    JsonNode tables = protocolGet(token, "/shares/" + share + "/schemas/sales/tables");
    assertEquals(2, tables.get("items").size());

    mvc.perform(
            get(PROTOCOL_BASE + "/shares/" + share + "/schemas/nope/tables")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
  }

  @Test
  void vendsTableCredentialsScopedToTheTableLocation() throws Exception {
    JsonNode credentials =
        protocolPost(
            token, "/shares/" + share + "/schemas/sales/tables/orders/temporary-table-credentials");

    assertTrue(credentials.get("awsTempCredentials").get("accessKeyId").asText().startsWith("ASIA"));
    assertFalse(credentials.get("awsTempCredentials").get("sessionToken").asText().isBlank());
    assertTrue(credentials.get("expirationTime").asLong() > System.currentTimeMillis());
    assertTrue(credentials.path("azureUserDelegationSas").isMissingNode());
  }

  @Test
  void vendsTableCredentialsForAnAuxiliaryLocation() throws Exception {
    mvc.perform(
            post(PROTOCOL_BASE
                    + "/shares/"
                    + share
                    + "/schemas/sales/tables/orders/temporary-table-credentials")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"location\":\"s3://acme-overflow/sales/orders/\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.awsTempCredentials.accessKeyId").exists());

    mvc.perform(
            post(PROTOCOL_BASE
                    + "/shares/"
                    + share
                    + "/schemas/sales/tables/orders/temporary-table-credentials")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"location\":\"s3://somewhere-else/\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER_VALUE"));
  }

  @Test
  void refusesARequestFromOutsideTheRecipientsIpAccessList() throws Exception {
    adminPatch("/recipients/" + recipient, "{\"ip_access_list\":[\"203.0.113.0/24\"]}");

    mvc.perform(get(PROTOCOL_BASE + "/shares").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("PERMISSION_DENIED"));

    // MockMvc requests come from 127.0.0.1, so allowing loopback lets the same call through.
    adminPatch("/recipients/" + recipient, "{\"ip_access_list\":[\"127.0.0.0/8\"]}");
    protocolGet(token, "/shares");
  }

  @Test
  void rejectsMissingInvalidAndRevokedTokens() throws Exception {
    mvc.perform(get(PROTOCOL_BASE + "/shares"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));

    mvc.perform(get(PROTOCOL_BASE + "/shares").header("Authorization", "Bearer os_nonsense"))
        .andExpect(status().isUnauthorized());

    // Rotating without a grace window is how a compromised token is taken out of service.
    rotateToken(recipient, "{\"existing_token_expire_in_seconds\":0}");

    mvc.perform(get(PROTOCOL_BASE + "/shares").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void paginatesWithOpaqueTokens() throws Exception {
    JsonNode firstPage = protocolGet(token, "/shares/" + share + "/all-tables?maxResults=2");
    assertEquals(2, firstPage.get("items").size());
    String nextPageToken = firstPage.get("nextPageToken").asText();
    assertFalse(nextPageToken.isBlank());

    JsonNode secondPage =
        protocolGet(
            token, "/shares/" + share + "/all-tables?maxResults=2&pageToken=" + nextPageToken);
    assertEquals(1, secondPage.get("items").size());
    assertTrue(secondPage.path("nextPageToken").isMissingNode(), "the last page has no token");
    assertFalse(
        names(firstPage).contains(secondPage.get("items").get(0).get("name").asText()),
        "pages must not repeat items");

    mvc.perform(
            get(PROTOCOL_BASE + "/shares/" + share + "/all-tables?pageToken=not-a-token")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER_VALUE"));
  }

  @Test
  void icebergHandshakeReportsTheShareAsThePathPrefix() throws Exception {
    JsonNode config = protocolGet(token, "/iceberg/v1/config?warehouse=" + share);

    assertEquals("shares/" + share, config.get("overrides").get("prefix").asText());
    assertEquals(5, config.get("endpoints").size());

    // A share the recipient cannot see is not a valid warehouse.
    String otherShare = createShare(unique("other_share"));
    mvc.perform(
            get(PROTOCOL_BASE + "/iceberg/v1/config?warehouse=" + otherShare)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());

    mvc.perform(get(PROTOCOL_BASE + "/iceberg/v1/config?warehouse=" + share))
        .andExpect(status().isUnauthorized());

    // Leaving out the warehouse entirely is the client's mistake, not the server's.
    mvc.perform(
            get(PROTOCOL_BASE + "/iceberg/v1/config").header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.message").value("warehouse is required"));
  }

  /**
   * The same share, seen through the Iceberg catalog: its schemas are namespaces, and of the three
   * tables in it only the Iceberg one is a table this catalog can hand over.
   */
  @Test
  void icebergNamespacesAreTheSchemasOfTheShareAndHoldOnlyItsIcebergTables() throws Exception {
    String prefix = "/iceberg/v1/shares/" + share;
    JsonNode namespaces = protocolGet(token, prefix + "/namespaces");

    assertEquals(List.of("research"), levels(namespaces.get("namespaces").get(0)));
    assertEquals(List.of("sales"), levels(namespaces.get("namespaces").get(1)));

    JsonNode sales = protocolGet(token, prefix + "/namespaces/sales");
    assertEquals(List.of("sales"), levels(sales.get("namespace")));

    JsonNode tables = protocolGet(token, prefix + "/namespaces/sales/tables");
    assertEquals(1, tables.get("identifiers").size(), "sales.orders is a Delta table");
    assertEquals("forecast", tables.get("identifiers").get(0).get("name").asText());

    mvc.perform(
            get(PROTOCOL_BASE + prefix + "/namespaces/marketing")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value(404))
        .andExpect(jsonPath("$.error.type").value("RESOURCE_DOES_NOT_EXIST"));
  }

  private static List<String> levels(JsonNode namespace) {
    List<String> levels = new ArrayList<>();
    namespace.forEach(level -> levels.add(level.asText()));
    return levels;
  }

  /**
   * The test catalog puts this table on S3, which a test points at a port nothing listens on, so this
   * is the other half of url access mode: what a recipient is told when the table's storage does not
   * answer. Nothing about the request is wrong and a later attempt may work, so it is reported as a
   * failure upstream rather than as a refusal.
   */
  @Test
  void tellsARecipientWhenTheTablesStorageDoesNotAnswer() throws Exception {
    mvc.perform(
            get(PROTOCOL_BASE + "/shares/" + share + "/schemas/sales/tables/orders/metadata")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.message").value(containsString("could not be reached")));
  }

  private static JsonNode itemNamed(JsonNode listResponse, String name) {
    for (JsonNode item : listResponse.get("items")) {
      if (item.get("name").asText().equals(name)) {
        return item;
      }
    }
    throw new AssertionError("no item named '" + name + "' in " + listResponse);
  }

  private static List<String> names(JsonNode listResponse) {
    List<String> names = new ArrayList<>();
    listResponse.get("items").forEach(item -> names.add(item.get("name").asText()));
    return names;
  }
}
