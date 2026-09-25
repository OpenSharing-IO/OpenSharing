package io.opensharing.asset.table;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opensharing.asset.ProtocolApiSupport;
import io.opensharing.http.ErrorCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Recipient Query Table Version through the local catalog, Kernel, and a real Delta log.
 * Skipped unless same-repository cloud credentials are present.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:protocol-table-version;DB_CLOSE_DELAY=-1",
      "opensharing.catalog.type=local",
      "opensharing.catalog.local.file=classpath:local-catalog-s3.yml",
      "opensharing.test.stub-protocol-dependencies=false"
    })
@EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
@Timeout(60)
class ProtocolTableVersionIT extends ProtocolApiSupport {

  @Test
  void queriesLatestAndTimestampVersionsForTable1() throws Exception {
    String bearer = shareTable("table1-version", "main.sales.table1", "sales.table1");
    String endpoint = PROTOCOL + "/shares/table1-version/schemas/sales/tables/table1/version";

    version(endpoint, bearer, null)
        .andExpect(status().isOk())
        .andExpect(header().string("Delta-Table-Version", "2"))
        .andExpect(content().string(""));

    version(endpoint, bearer, "1970-01-01T00:00:00Z")
        .andExpect(status().isOk())
        .andExpect(header().string("Delta-Table-Version", "0"));
    version(endpoint, bearer, "2099-01-01T00:00:00Z")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
    version(endpoint, bearer, "not-a-timestamp")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
  }

  @Test
  void queriesAtOrAfterTimestampsOnTheStartingTimestampTable() throws Exception {
    String bearer =
        shareTable("ts-version", "main.sales.startingtimestamp", "sales.startingtimestamp");
    String endpoint =
        PROTOCOL + "/shares/ts-version/schemas/sales/tables/startingtimestamp/version";

    version(endpoint, bearer, null)
        .andExpect(status().isOk())
        .andExpect(header().string("Delta-Table-Version", "5"));
    version(endpoint, bearer, "2023-05-09T08:00:00Z")
        .andExpect(status().isOk())
        .andExpect(header().string("Delta-Table-Version", "0"));
    version(endpoint, bearer, "2023-05-09T08:32:55.517Z")
        .andExpect(status().isOk())
        .andExpect(header().string("Delta-Table-Version", "3"));
    version(endpoint, bearer, "2023-05-09T08:45:00Z")
        .andExpect(status().isOk())
        .andExpect(header().string("Delta-Table-Version", "4"));
    version(endpoint, bearer, "2023-05-09T09:09:58.088Z")
        .andExpect(status().isOk())
        .andExpect(header().string("Delta-Table-Version", "5"));
    version(endpoint, bearer, "2023-05-09T10:00:00Z")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
  }

  @Test
  void queriesVersionThroughASchemaGrant() throws Exception {
    createShare("schema-version");
    addObject("schema-version", "SCHEMA", "main.sales", "sales");
    String bearer = createAndActivateRecipient("schema-version-partner");
    grant("schema-version", "schema-version-partner");

    version(
            PROTOCOL + "/shares/schema-version/schemas/SALES/tables/TABLE1/version",
            bearer,
            null)
        .andExpect(status().isOk())
        .andExpect(header().string("Delta-Table-Version", "2"));
    version(
            PROTOCOL
                + "/shares/schema-version/schemas/sales/tables/startingtimestamp/version",
            bearer,
            "2023-05-09T08:32:00Z")
        .andExpect(status().isOk())
        .andExpect(header().string("Delta-Table-Version", "2"));
  }

  @Test
  void rejectsVersionForMissingOrUngrantedTables() throws Exception {
    createShare("hidden-version");
    addObject("hidden-version", "TABLE", "main.sales.table1", "sales.table1");
    String bearer = shareTable("granted-version", "main.sales.table1", "sales.table1");

    version(
            PROTOCOL + "/shares/granted-version/schemas/sales/tables/missing/version",
            bearer,
            null)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
    version(
            PROTOCOL + "/shares/hidden-version/schemas/sales/tables/table1/version",
            bearer,
            null)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
  }

  private String shareTable(String share, String catalogName, String sharedAs) throws Exception {
    createShare(share);
    addObject(share, "TABLE", catalogName, sharedAs);
    String bearer = createAndActivateRecipient(share + "-partner");
    grant(share, share + "-partner");
    return bearer;
  }

  private ResultActions version(String endpoint, String bearer, String startingTimestamp)
      throws Exception {
    var request = get(endpoint).header("Authorization", "Bearer " + bearer);
    if (startingTimestamp != null) {
      request = request.param("startingTimestamp", startingTimestamp);
    }
    return mvc.perform(request);
  }
}
