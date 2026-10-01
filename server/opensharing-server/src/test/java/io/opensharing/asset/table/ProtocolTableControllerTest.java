package io.opensharing.asset.table;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opensharing.asset.ProtocolApiSupport;
import io.opensharing.http.ErrorCodes;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:protocol-tables;DB_CLOSE_DELAY=-1")
class ProtocolTableControllerTest extends ProtocolApiSupport {

  @Test
  void listsGrantedTablesInASchema() throws Exception {
    createShare("table-share");
    addObject("table-share", "TABLE", "main.sales.orders", "sales.orders");
    addObject("table-share", "TABLE", "main.sales.customers", "sales.customers");
    addObject("table-share", "TABLE", "main.ops.people", "ops.people");
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
        .andExpect(jsonPath("$.items[0].id").value("main.sales.customers"))
        .andExpect(jsonPath("$.items[0].location").value("s3://test/main.sales.customers/"))
        .andExpect(jsonPath("$.items[0].accessModes[0]").value("url"))
        .andExpect(jsonPath("$.items[0].accessModes[1]").value("dir"))
        .andExpect(jsonPath("$.items[1].name").value("orders"))
        .andExpect(jsonPath("$.nextPageToken").doesNotExist());

    mvc.perform(
            get(PROTOCOL + "/shares/table-share/schemas/missing/tables")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
  }

  @Test
  void listsCatalogChildrenOfASharedSchema() throws Exception {
    createShare("schema-tables");
    addObject("schema-tables", "SCHEMA", "main.hr", "hr");
    createShare("hidden-share");
    addObject("hidden-share", "TABLE", "main.sales.orders", "sales.orders");
    String bearer = createAndActivateRecipient("schema-tables-partner");
    grant("schema-tables", "schema-tables-partner");

    mvc.perform(
            get(PROTOCOL + "/shares/schema-tables/schemas/hr/tables")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].name").value("employees"))
        .andExpect(jsonPath("$.items[0].location").value("s3://test/main.hr.employees/"))
        .andExpect(jsonPath("$.items[0].id").value("main.hr.employees"))
        .andExpect(jsonPath("$.items[1].name").value("salaries"))
        .andExpect(jsonPath("$.items[1].location").value("s3://test/main.hr.salaries/"))
        .andExpect(jsonPath("$.items[1].id").value("main.hr.salaries"))
        .andExpect(jsonPath("$.items[1].accessModes[0]").value("url"))
        .andExpect(jsonPath("$.items[1].accessModes[1]").value("dir"));

    mvc.perform(
            get(PROTOCOL + "/shares/hidden-share/schemas/sales/tables")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
  }

  @Test
  void paginatesTablesInASchema() throws Exception {
    createShare("paged-tables");
    addObject("paged-tables", "TABLE", "main.sales.table1", "sales.a");
    addObject("paged-tables", "TABLE", "main.sales.orders", "sales.b");
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
    addObject("all-tables", "TABLE", "main.sales.orders", "sales.orders");
    addObject("all-tables", "SCHEMA", "main.hr", "hr");
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

  @Test
  void queriesAGrantedTableVersion() throws Exception {
    createShare("table-version");
    addObject("table-version", "TABLE", "main.sales.orders", "sales.orders");
    addObject("table-version", "SCHEMA", "main.hr", "hr");
    createShare("hidden-version");
    addObject("hidden-version", "TABLE", "main.sales.customers", "sales.customers");
    String bearer = createAndActivateRecipient("table-version-partner");
    grant("table-version", "table-version-partner");

    mvc.perform(
            get(PROTOCOL + "/shares/TABLE-VERSION/schemas/SALES/tables/ORDERS/version")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andExpect(header().string("Delta-Table-Version", "123"))
        .andExpect(content().string(""));

    mvc.perform(
            get(PROTOCOL + "/shares/table-version/schemas/hr/tables/SALARIES/version")
                .param("startingTimestamp", "2022-01-01T00:00:00Z")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andExpect(header().string("Delta-Table-Version", "45"))
        .andExpect(content().string(""));

    mvc.perform(
            get(PROTOCOL + "/shares/table-version/schemas/sales/tables/orders/version")
                .param("startingTimestamp", "not-a-timestamp")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
    mvc.perform(
            get(PROTOCOL + "/shares/table-version/schemas/hr/tables/missing/version")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
    mvc.perform(
            get(PROTOCOL + "/shares/hidden-version/schemas/sales/tables/customers/version")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
  }

  @Test
  void queriesGrantedTableMetadata() throws Exception {
    createShare("table-metadata");
    addObject("table-metadata", "TABLE", "main.sales.orders", "sales.orders");
    addObject("table-metadata", "SCHEMA", "main.hr", "hr");
    createShare("hidden-metadata");
    addObject("hidden-metadata", "TABLE", "main.sales.customers", "sales.customers");
    String bearer = createAndActivateRecipient("table-metadata-partner");
    grant("table-metadata", "table-metadata-partner");

    // Latest metadata defaults to parquet NDJSON (protocol + metaData.schemaString).
    String latest =
        mvc.perform(
                get(PROTOCOL + "/shares/TABLE-METADATA/schemas/SALES/tables/ORDERS/metadata")
                    .header("Authorization", "Bearer " + bearer))
            .andExpect(status().isOk())
            .andExpect(header().string("Delta-Table-Version", "123"))
            .andExpect(header().string("delta-sharing-capabilities", "responseformat=parquet"))
            .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertTrue(latest.contains("\"protocol\""));
    assertTrue(latest.contains("\"metaData\""));
    assertTrue(latest.contains("\"schemaString\""));
    assertFalse(latest.contains("\"deltaMetadata\""));

    // responseformat=delta wraps Kernel protocol and metadata as deltaProtocol/deltaMetadata.
    String delta =
        mvc.perform(
                get(PROTOCOL + "/shares/table-metadata/schemas/sales/tables/orders/metadata")
                    .header("Authorization", "Bearer " + bearer)
                    .header("delta-sharing-capabilities", "responseformat=delta"))
            .andExpect(status().isOk())
            .andExpect(header().string("Delta-Table-Version", "123"))
            .andExpect(header().string("delta-sharing-capabilities", "responseformat=delta"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertTrue(delta.contains("\"deltaProtocol\""), delta);
    assertTrue(delta.contains("\"deltaMetadata\""), delta);
    // Unknown responseformat and capability keys are INVALID_PARAMETER_VALUE.
    mvc.perform(
            get(PROTOCOL + "/shares/table-metadata/schemas/sales/tables/orders/metadata")
                .header("Authorization", "Bearer " + bearer)
                .header("delta-sharing-capabilities", "responseformat=json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
    mvc.perform(
            get(PROTOCOL + "/shares/table-metadata/schemas/sales/tables/orders/metadata")
                .header("Authorization", "Bearer " + bearer)
                .header("delta-sharing-capabilities", "unknown=true"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));

    // timestamp-as-of uses the historical snapshot version from the stub Kernel.
    mvc.perform(
            get(PROTOCOL + "/shares/table-metadata/schemas/hr/tables/SALARIES/metadata")
                .param("timestamp", "2022-01-01T00:00:00Z")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andExpect(header().string("Delta-Table-Version", "45"));
    // version and timestamp together are mutually exclusive.
    mvc.perform(
            get(PROTOCOL + "/shares/table-metadata/schemas/sales/tables/orders/metadata")
                .param("version", "1")
                .param("timestamp", "2022-01-01T00:00:00Z")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
    // A malformed timestamp is INVALID_PARAMETER_VALUE.
    mvc.perform(
            get(PROTOCOL + "/shares/table-metadata/schemas/sales/tables/orders/metadata")
                .param("timestamp", "not-a-timestamp")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
    // A table in an ungranted share is 404, not 403.
    mvc.perform(
            get(PROTOCOL + "/shares/hidden-metadata/schemas/sales/tables/customers/metadata")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
  }

  @Test
  void vendsTemporaryCredentialsForAGrantedTable() throws Exception {
    createShare("table-creds");
    addObject("table-creds", "TABLE", "main.sales.orders", "sales.orders");
    addObject("table-creds", "SCHEMA", "main.hr", "hr");
    createShare("hidden-creds");
    addObject("hidden-creds", "TABLE", "main.sales.customers", "sales.customers");
    String bearer = createAndActivateRecipient("table-creds-partner");
    grant("table-creds", "table-creds-partner");

    // Empty body vends FAKE AWS credentials for the table root.
    mvc.perform(
            post(PROTOCOL
                    + "/shares/TABLE-CREDS/schemas/SALES/tables/ORDERS/temporary-table-credentials")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.credentials.location").value("s3://test/main.sales.orders/"))
        .andExpect(jsonPath("$.credentials.awsTempCredentials.accessKeyId").value(startsWith("ASIA")))
        .andExpect(jsonPath("$.credentials.awsTempCredentials.secretAccessKey").exists())
        .andExpect(jsonPath("$.credentials.awsTempCredentials.sessionToken").exists())
        .andExpect(jsonPath("$.credentials.azureUserDelegationSas").doesNotExist())
        .andExpect(jsonPath("$.credentials.expirationTime").value(greaterThan(0L)));
    // Explicit location may repeat the table root.
    mvc.perform(
            post(PROTOCOL
                    + "/shares/table-creds/schemas/sales/tables/orders/temporary-table-credentials")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"location\":\"s3://test/main.sales.orders/\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.credentials.location").value("s3://test/main.sales.orders/"));
    // A SCHEMA grant can mint credentials for a catalog child.
    mvc.perform(
            post(PROTOCOL
                    + "/shares/table-creds/schemas/hr/tables/employees/temporary-table-credentials")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.credentials.location").value("s3://test/main.hr.employees/"));
    // A location outside the table root and auxiliaries is INVALID_PARAMETER_VALUE.
    mvc.perform(
            post(PROTOCOL
                    + "/shares/table-creds/schemas/sales/tables/orders/temporary-table-credentials")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"location\":\"s3://other/prefix/\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
    // A table in an ungranted share is 404, not 403.
    mvc.perform(
            post(PROTOCOL
                    + "/shares/hidden-creds/schemas/sales/tables/customers/temporary-table-credentials")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
  }

  @Test
  void queriesGrantedTable() throws Exception {
    createShare("table-query");
    addObject("table-query", "TABLE", "main.sales.orders", "sales.orders");
    addObject("table-query", "SCHEMA", "main.hr", "hr");
    createShare("hidden-query");
    addObject("hidden-query", "TABLE", "main.sales.customers", "sales.customers");
    String bearer = createAndActivateRecipient("table-query-partner");
    grant("table-query", "table-query-partner");

    // Latest snapshot query defaults to parquet NDJSON (protocol + metaData + file).
    String latest =
        mvc.perform(
                post(PROTOCOL + "/shares/TABLE-QUERY/schemas/SALES/tables/ORDERS/query")
                    .header("Authorization", "Bearer " + bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isOk())
            .andExpect(header().string("Delta-Table-Version", "123"))
            .andExpect(header().string("delta-sharing-capabilities", "responseformat=parquet"))
            .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertTrue(latest.contains("\"protocol\""), latest);
    assertTrue(latest.contains("\"metaData\""), latest);
    assertTrue(latest.contains("\"schemaString\""), latest);
    assertTrue(latest.contains("\"file\""), latest);
    assertTrue(latest.contains("https://example.invalid/stub.parquet"), latest);
    assertFalse(latest.contains("\"deltaSingleAction\""), latest);

    // fileidhash=parquet is echoed; unsupported values are INVALID_PARAMETER_VALUE.
    mvc.perform(
            post(PROTOCOL + "/shares/table-query/schemas/sales/tables/orders/query")
                .header("Authorization", "Bearer " + bearer)
                .header(FileIdHash.HEADER, "PARQUET")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(header().string(FileIdHash.HEADER, "parquet"));
    mvc.perform(
            post(PROTOCOL + "/shares/table-query/schemas/sales/tables/orders/query")
                .header("Authorization", "Bearer " + bearer)
                .header(FileIdHash.HEADER, "md5")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));

    // responseformat=delta wraps each file as deltaSingleAction.add with path as the URL.
    String delta =
        mvc.perform(
                post(PROTOCOL + "/shares/table-query/schemas/sales/tables/orders/query")
                    .header("Authorization", "Bearer " + bearer)
                    .header("delta-sharing-capabilities", "responseformat=delta")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isOk())
            .andExpect(header().string("Delta-Table-Version", "123"))
            .andExpect(header().string("delta-sharing-capabilities", "responseformat=delta"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertTrue(delta.contains("\"deltaProtocol\""), delta);
    assertTrue(delta.contains("\"deltaSingleAction\""), delta);

    // includeRefreshToken appends endStreamAction with a refresh token; the ESA header is echoed
    // only when the request asked for includeendstreamaction.
    String refreshed =
        mvc.perform(
                post(PROTOCOL + "/shares/table-query/schemas/sales/tables/orders/query")
                    .header("Authorization", "Bearer " + bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"includeRefreshToken\":true}"))
            .andExpect(status().isOk())
            .andExpect(header().string("delta-sharing-capabilities", "responseformat=parquet"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertTrue(refreshed.contains("\"endStreamAction\""), refreshed);
    assertTrue(refreshed.contains("\"refreshToken\""), refreshed);
    String withEsa =
        mvc.perform(
                post(PROTOCOL + "/shares/table-query/schemas/sales/tables/orders/query")
                    .header("Authorization", "Bearer " + bearer)
                    .header("delta-sharing-capabilities", "includeendstreamaction=true")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isOk())
            .andExpect(
                header()
                    .string(
                        "delta-sharing-capabilities",
                        "responseformat=parquet;includeendstreamaction=true"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertTrue(withEsa.contains("\"endStreamAction\""), withEsa);
    assertFalse(withEsa.contains("\"refreshToken\""), withEsa);
    mvc.perform(
            post(PROTOCOL + "/shares/table-query/schemas/sales/tables/orders/query")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"includeRefreshToken\":true,\"version\":1}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));

    // Unknown responseformat is INVALID_PARAMETER_VALUE.
    mvc.perform(
            post(PROTOCOL + "/shares/table-query/schemas/sales/tables/orders/query")
                .header("Authorization", "Bearer " + bearer)
                .header("delta-sharing-capabilities", "responseformat=json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));

    // timestamp-as-of uses the historical snapshot version from the stub Kernel.
    mvc.perform(
            post(PROTOCOL + "/shares/table-query/schemas/hr/tables/SALARIES/query")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"timestamp\":\"2022-01-01T00:00:00Z\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string("Delta-Table-Version", "45"));
    // version and timestamp together are mutually exclusive.
    mvc.perform(
            post(PROTOCOL + "/shares/table-query/schemas/sales/tables/orders/query")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1,\"timestamp\":\"2022-01-01T00:00:00Z\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
    // A table in an ungranted share is 404, not 403.
    mvc.perform(
            post(PROTOCOL + "/shares/hidden-query/schemas/sales/tables/customers/query")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
  }
}
