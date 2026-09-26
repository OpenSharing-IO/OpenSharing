package io.opensharing.asset.schema;

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

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:protocol-schemas;DB_CLOSE_DELAY=-1")
class ProtocolSchemaControllerTest extends ProtocolApiSupport {

  @Test
  void listsDistinctSchemasInAGrantedShare() throws Exception {
    createShare("schema-share");
    addObject("schema-share", "TABLE", "main.sales.table1", "alpha.t1");
    addObject("schema-share", "TABLE", "main.sales.orders", "alpha.t2");
    addObject("schema-share", "SCHEMA", "main.hr", "beta");
    addObject("schema-share", "TABLE", "main.ops.people", "gamma.t3");
    createShare("hidden-share");
    addObject("hidden-share", "TABLE", "main.sales.table1", "hidden.t");
    String bearer = createAndActivateRecipient("schema-partner");
    grant("schema-share", "schema-partner");

    // Distinct shared-as schemas, ordered by name; an ungranted share is not listed.
    mvc.perform(
            get(PROTOCOL + "/shares/SCHEMA-SHARE/schemas")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().encoding("UTF-8"))
        .andExpect(jsonPath("$.items.length()").value(3))
        .andExpect(jsonPath("$.items[0].name").value("alpha"))
        .andExpect(jsonPath("$.items[0].share").value("schema-share"))
        .andExpect(jsonPath("$.items[1].name").value("beta"))
        .andExpect(jsonPath("$.items[2].name").value("gamma"))
        .andExpect(jsonPath("$.nextPageToken").doesNotExist());

    mvc.perform(
            get(PROTOCOL + "/shares/hidden-share/schemas")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
    mvc.perform(
            get(PROTOCOL + "/shares/missing/schemas").header("Authorization", "Bearer " + bearer))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
  }

  @Test
  void paginatesSchemasAndAllowsAnEmptyGrantedShare() throws Exception {
    createShare("paged-schemas");
    addObject("paged-schemas", "TABLE", "main.sales.table1", "alpha.t");
    addObject("paged-schemas", "TABLE", "main.sales.orders", "beta.t");
    createShare("empty-share");
    String bearer = createAndActivateRecipient("schema-paged");
    grant("paged-schemas", "schema-paged");
    grant("empty-share", "schema-paged");

    String first =
        mvc.perform(
                get(PROTOCOL + "/shares/paged-schemas/schemas")
                    .param("maxResults", "1")
                    .header("Authorization", "Bearer " + bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].name").value("alpha"))
            .andExpect(jsonPath("$.nextPageToken").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String pageToken = JsonPath.read(first, "$.nextPageToken");
    mvc.perform(
            get(PROTOCOL + "/shares/paged-schemas/schemas")
                .param("maxResults", "1")
                .param("pageToken", pageToken)
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].name").value("beta"))
        .andExpect(jsonPath("$.nextPageToken").doesNotExist());

    mvc.perform(
            get(PROTOCOL + "/shares/empty-share/schemas")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.nextPageToken").doesNotExist());
  }
}
