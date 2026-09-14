package io.opensharing;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Sharing a whole schema. The provider names the schema once and the tables in it follow, so these
 * tests are mostly about a recipient seeing tables nobody ever added to the share.
 */
class SchemaSharingApiTest extends ServerTestBase {

  private String share;
  private String token;

  @BeforeEach
  void shareASchemaWithARecipient() throws Exception {
    share = createShare(unique("hr_share"));
    String recipient = unique("partner");
    token = createRecipientWithToken(recipient);
    grant(share, recipient);
  }

  @Test
  void recordsTheGrantUnderAOneLevelAlias() throws Exception {
    JsonNode grant = addSchema(share, "hr", "main.hr");

    assertEquals("SCHEMA", grant.get("type").asText());
    assertEquals("hr", grant.get("shared_as").asText());
    assertEquals("main.hr", grant.get("name").asText());
  }

  @Test
  void defaultsTheAliasToTheSchemasOwnName() throws Exception {
    JsonNode updated =
        adminPatch(
            "/shares/" + share,
            "{\"updates\":[{\"action\":\"ADD\",\"data_object\":"
                + "{\"name\":\"main.hr\",\"type\":\"SCHEMA\"}}]}");

    assertEquals("hr", objectSharedAs(updated, "hr").get("shared_as").asText());
  }

  @Test
  void showsTheSchemaToTheRecipient() throws Exception {
    addSchema(share, "hr", "main.hr");

    assertEquals(List.of("hr"), names(protocolGet(token, "/shares/" + share + "/schemas")));
  }

  @Test
  void listsTheTablesTheCatalogPutsInTheSchema() throws Exception {
    addSchema(share, "hr", "main.hr");

    JsonNode tables = protocolGet(token, "/shares/" + share + "/schemas/hr/tables");

    assertEquals(List.of("employees", "salaries"), names(tables));
    JsonNode employees = tables.get("items").get(0);
    assertEquals("hr", employees.get("schema").asText());
    assertEquals(share, employees.get("share").asText());
    assertEquals("s3://acme-lake/hr/employees/", employees.get("location").asText());
    assertTrue(employees.get("accessModes").toString().contains("dir"));
  }

  @Test
  void countsTheSchemasTablesAmongAllTables() throws Exception {
    addSchema(share, "hr", "main.hr");
    addTable(share, "sales.orders", "main.sales.orders");

    assertEquals(
        List.of("employees", "salaries", "orders"),
        names(protocolGet(token, "/shares/" + share + "/all-tables")));
  }

  @Test
  void vendsCredentialsForATableNobodyAddedByName() throws Exception {
    addSchema(share, "hr", "main.hr");

    JsonNode credentials =
        protocolPost(
            token, "/shares/" + share + "/schemas/hr/tables/employees/temporary-table-credentials");

    assertTrue(credentials.get("awsTempCredentials").has("accessKeyId"));
    assertTrue(credentials.get("expirationTime").asLong() > 0);
  }

  @Test
  void findsATableWhateverCaseItIsAskedFor() throws Exception {
    addSchema(share, "HR", "main.hr");

    JsonNode tables = protocolGet(token, "/shares/" + share + "/schemas/hr/tables");

    assertEquals("HR", tables.get("items").get(0).get("schema").asText(), "the alias as given");
    protocolPost(
        token, "/shares/" + share + "/schemas/hr/tables/EMPLOYEES/temporary-table-credentials");
  }

  @Test
  void refusesATableTheSchemaDoesNotHold() throws Exception {
    addSchema(share, "hr", "main.hr");

    mvc.perform(
            post(PROTOCOL_BASE
                    + "/shares/"
                    + share
                    + "/schemas/hr/tables/ghost/temporary-table-credentials")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value(containsString("ghost")));
  }

  @Test
  void prefersATableSharedInItsOwnRightOverTheSchemasOwn() throws Exception {
    addSchema(share, "hr", "main.hr");
    addTable(share, "hr.employees", "main.sales.orders");

    JsonNode tables = protocolGet(token, "/shares/" + share + "/schemas/hr/tables");

    assertEquals(List.of("employees", "salaries"), names(tables));
    assertEquals(
        "s3://acme-lake/sales/orders/",
        tables.get("items").get(0).get("location").asText(),
        "the table the provider named deliberately, not the schema's own");
  }

  @Test
  void pagesThroughTheSchemasTables() throws Exception {
    addSchema(share, "hr", "main.hr");

    JsonNode first = protocolGet(token, "/shares/" + share + "/schemas/hr/tables?maxResults=1");
    assertEquals(List.of("employees"), names(first));
    String pageToken = first.get("nextPageToken").asText();

    JsonNode second =
        protocolGet(
            token, "/shares/" + share + "/schemas/hr/tables?maxResults=1&pageToken=" + pageToken);
    assertEquals(List.of("salaries"), names(second));
    assertFalse(second.has("nextPageToken"), "the schema holds nothing more");
  }

  @Test
  void refusesTwoSchemasSharedUnderOneAlias() throws Exception {
    addSchema(share, "hr", "main.hr");

    mvc.perform(
            patch(ADMIN_BASE + "/shares/" + share)
                .header("Authorization", "Bearer " + ALICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"updates\":[{\"action\":\"ADD\",\"data_object\":"
                        + "{\"name\":\"main.people\",\"type\":\"SCHEMA\",\"shared_as\":\"hr\"}}]}"))
        .andExpect(status().isConflict());
  }

  @Test
  void refusesATwoLevelAliasForASchema() throws Exception {
    mvc.perform(
            patch(ADMIN_BASE + "/shares/" + share)
                .header("Authorization", "Bearer " + ALICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"updates\":[{\"action\":\"ADD\",\"data_object\":"
                        + "{\"name\":\"main.hr\",\"type\":\"SCHEMA\",\"shared_as\":\"hr.people\"}}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("one-level")));
  }

  @Test
  void refusesASchemaTheCatalogDoesNotHave() throws Exception {
    mvc.perform(
            patch(ADMIN_BASE + "/shares/" + share)
                .header("Authorization", "Bearer " + ALICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"updates\":[{\"action\":\"ADD\",\"data_object\":"
                        + "{\"name\":\"main.nope\",\"type\":\"SCHEMA\"}}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("does not exist")));
  }

  @Test
  void stopsSharingTheTablesWhenTheGrantIsRemoved() throws Exception {
    addSchema(share, "hr", "main.hr");

    adminPatch(
        "/shares/" + share,
        "{\"updates\":[{\"action\":\"REMOVE\",\"data_object\":"
            + "{\"name\":\"main.hr\",\"shared_as\":\"hr\"}}]}");

    assertEquals(List.of(), names(protocolGet(token, "/shares/" + share + "/schemas")));
    mvc.perform(
            get(PROTOCOL_BASE + "/shares/" + share + "/schemas/hr/tables")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
  }

  private JsonNode addSchema(String share, String sharedAs, String catalogName) throws Exception {
    JsonNode updated =
        adminPatch(
            "/shares/" + share,
            "{\"updates\":[{\"action\":\"ADD\",\"data_object\":{\"name\":\""
                + catalogName
                + "\",\"type\":\"SCHEMA\",\"shared_as\":\""
                + sharedAs
                + "\"}}]}");
    return objectSharedAs(updated, sharedAs);
  }

  private static List<String> names(JsonNode listResponse) {
    List<String> names = new ArrayList<>();
    for (JsonNode item : listResponse.get("items")) {
      names.add(item.get("name").asText());
    }
    return names;
  }
}
