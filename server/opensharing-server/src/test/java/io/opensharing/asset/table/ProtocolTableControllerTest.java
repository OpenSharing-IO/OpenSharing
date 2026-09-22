package io.opensharing.asset.table;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opensharing.asset.ProtocolApiSupport;
import io.opensharing.http.ErrorCodes;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:protocol-tables;DB_CLOSE_DELAY=-1",
      "opensharing.catalog.type=local",
      "opensharing.catalog.local.file=classpath:local-catalog.yml"
    })
class ProtocolTableControllerTest extends ProtocolApiSupport {

  @Test
  void listsGrantedTablesInASchema() throws Exception {
    createShare("table-share");
    addObject("table-share", "TABLE", "catalog.sales.orders", "sales.orders");
    addObject("table-share", "TABLE", "catalog.sales.customers", "sales.customers");
    addObject("table-share", "TABLE", "catalog.hr.people", "hr.people");
    String bearer = createAndActivateRecipient("table-partner");
    grant("table-share", "table-partner");

    mvc.perform(
            get(PROTOCOL + "/shares/TABLE-SHARE/schemas/SALES/tables")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().encoding("UTF-8"))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].name").value("customers"))
        .andExpect(jsonPath("$.items[0].schema").value("sales"))
        .andExpect(jsonPath("$.items[0].share").value("table-share"))
        .andExpect(jsonPath("$.items[0].shareId").exists())
        .andExpect(jsonPath("$.items[0].id").exists())
        .andExpect(jsonPath("$.items[0].location").value("s3://test/catalog.sales.customers/"))
        .andExpect(jsonPath("$.items[1].name").value("orders"))
        .andExpect(jsonPath("$.nextPageToken").doesNotExist());

    mvc.perform(
            get(PROTOCOL + "/shares/table-share/schemas/missing/tables")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
  }

  @Test
  void listsCatalogChildrenOfASharedSchemaAndPrefersAnExplicitTable() throws Exception {
    createShare("schema-tables");
    addObject("schema-tables", "SCHEMA", "catalog.hr", "hr");
    addObject("schema-tables", "TABLE", "catalog.other.orders", "hr.employees");
    createShare("hidden-share");
    addObject("hidden-share", "TABLE", "catalog.sales.orders", "sales.orders");
    String bearer = createAndActivateRecipient("schema-tables-partner");
    grant("schema-tables", "schema-tables-partner");

    mvc.perform(
            get(PROTOCOL + "/shares/schema-tables/schemas/hr/tables")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].name").value("employees"))
        .andExpect(jsonPath("$.items[0].location").value("s3://test/catalog.other.orders/"))
        .andExpect(jsonPath("$.items[0].id").exists())
        .andExpect(jsonPath("$.items[1].name").value("salaries"))
        .andExpect(jsonPath("$.items[1].location").value("s3://test/catalog.hr.salaries/"))
        .andExpect(jsonPath("$.items[1].id").doesNotExist());

    mvc.perform(
            get(PROTOCOL + "/shares/hidden-share/schemas/sales/tables")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
  }

  @Test
  void paginatesTablesInASchema() throws Exception {
    createShare("paged-tables");
    addObject("paged-tables", "TABLE", "catalog.sales.a", "sales.a");
    addObject("paged-tables", "TABLE", "catalog.sales.b", "sales.b");
    String bearer = createAndActivateRecipient("table-paged");
    grant("paged-tables", "table-paged");

    String first =
        mvc.perform(
                get(PROTOCOL + "/shares/paged-tables/schemas/sales/tables")
                    .param("maxResults", "1")
                    .header("Authorization", "Bearer " + bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].name").value("a"))
            .andExpect(jsonPath("$.nextPageToken").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String pageToken = JsonPath.read(first, "$.nextPageToken");
    mvc.perform(
            get(PROTOCOL + "/shares/paged-tables/schemas/sales/tables")
                .param("maxResults", "1")
                .param("pageToken", pageToken)
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].name").value("b"))
        .andExpect(jsonPath("$.nextPageToken").doesNotExist());
  }

  @Test
  void listsAllTablesInAGrantedShare() throws Exception {
    createShare("all-tables");
    addObject("all-tables", "TABLE", "catalog.sales.orders", "sales.orders");
    addObject("all-tables", "SCHEMA", "catalog.hr", "hr");
    createShare("empty-share");
    String bearer = createAndActivateRecipient("all-tables-partner");
    grant("all-tables", "all-tables-partner");
    grant("empty-share", "all-tables-partner");

    mvc.perform(
            get(PROTOCOL + "/shares/ALL-TABLES/all-tables")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.items.length()").value(3))
        .andExpect(jsonPath("$.items[0].schema").value("hr"))
        .andExpect(jsonPath("$.items[0].name").value("employees"))
        .andExpect(jsonPath("$.items[1].schema").value("hr"))
        .andExpect(jsonPath("$.items[1].name").value("salaries"))
        .andExpect(jsonPath("$.items[2].schema").value("sales"))
        .andExpect(jsonPath("$.items[2].name").value("orders"))
        .andExpect(jsonPath("$.nextPageToken").doesNotExist());

    String first =
        mvc.perform(
                get(PROTOCOL + "/shares/all-tables/all-tables")
                    .param("maxResults", "1")
                    .header("Authorization", "Bearer " + bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].name").value("employees"))
            .andExpect(jsonPath("$.nextPageToken").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String pageToken = JsonPath.read(first, "$.nextPageToken");
    mvc.perform(
            get(PROTOCOL + "/shares/all-tables/all-tables")
                .param("maxResults", "2")
                .param("pageToken", pageToken)
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].name").value("salaries"))
        .andExpect(jsonPath("$.items[1].name").value("orders"))
        .andExpect(jsonPath("$.nextPageToken").doesNotExist());

    mvc.perform(
            get(PROTOCOL + "/shares/empty-share/all-tables")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.nextPageToken").doesNotExist());
    mvc.perform(
            get(PROTOCOL + "/shares/missing/all-tables").header("Authorization", "Bearer " + bearer))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
  }
}
