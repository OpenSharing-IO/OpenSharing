package io.opensharing.asset.table;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Recipient Query Table through the local catalog, Kernel, and real cloud Delta logs. Individual
 * cloud cases are skipped unless their repository credentials are present.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:protocol-table-query;DB_CLOSE_DELAY=-1",
      "opensharing.test.stub-protocol-dependencies=false"
    })
@TestPropertySource(properties = "opensharing.catalog.local.file=classpath:local-catalog-cloud.yml")
@Timeout(60)
class ProtocolTableQueryIntegrationTest extends ProtocolApiSupport {

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void queriesLatestAndHistoricalFilesForTable1() throws Exception {
    String bearer = shareTable("table1-query", "main.sales.table1", "sales.table1");
    String endpoint = PROTOCOL + "/shares/table1-query/schemas/sales/tables/table1/query";

    // Latest snapshot defaults to parquet NDJSON at table1 version 2.
    String latest =
        query(endpoint, bearer, "{}", null)
            .andExpect(status().isOk())
            .andExpect(header().string("Delta-Table-Version", "2"))
            .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertParquetQuery(latest);
    assertTrue(latest.contains("https://"), latest);
    assertTrue(latest.contains("X-Amz-Signature="), latest);

    // Explicit parquet keeps protocol + schemaString + file.url, not delta wrappers.
    assertParquetQuery(
        query(endpoint, bearer, "{}", "responseformat=parquet")
            .andExpect(status().isOk())
            .andExpect(header().string("Delta-Table-Version", "2"))
            .andReturn()
            .getResponse()
            .getContentAsString());
    // Dual responseformat prefers delta over parquet.
    assertDeltaQuery(
        query(endpoint, bearer, "{}", "responseformat=delta,parquet")
            .andExpect(status().isOk())
            .andExpect(header().string("Delta-Table-Version", "2"))
            .andReturn()
            .getResponse()
            .getContentAsString());
    // Delta-only wraps Kernel add actions as deltaSingleAction.
    assertDeltaQuery(
        query(endpoint, bearer, "{}", "responseformat=delta")
            .andExpect(status().isOk())
            .andExpect(header().string("Delta-Table-Version", "2"))
            .andReturn()
            .getResponse()
            .getContentAsString());

    // Historical version=0 returns that snapshot.
    query(endpoint, bearer, "{\"version\":0}", null)
        .andExpect(status().isOk())
        .andExpect(header().string("Delta-Table-Version", "0"));
    // As-of the first commit time is also version 0.
    query(endpoint, bearer, "{\"timestamp\":\"2021-05-04T17:24:06Z\"}", null)
        .andExpect(status().isOk())
        .andExpect(header().string("Delta-Table-Version", "0"));
    // As-of before version 0 is INVALID_PARAMETER_VALUE.
    query(endpoint, bearer, "{\"timestamp\":\"1970-01-01T00:00:00Z\"}", null)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
    // version and timestamp together are mutually exclusive.
    query(endpoint, bearer, "{\"version\":1,\"timestamp\":\"2021-05-04T17:24:06Z\"}", null)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void queriesFilesThroughASchemaGrant() throws Exception {
    createShare("schema-query");
    addObject("schema-query", "SCHEMA", "main.sales", "sales");
    String bearer = createAndActivateRecipient("schema-query-partner");
    grant("schema-query", "schema-query-partner");

    // A SCHEMA grant can Query Table for catalog children (table1).
    assertParquetQuery(
        query(
                PROTOCOL + "/shares/schema-query/schemas/SALES/tables/TABLE1/query",
                bearer,
                "{}",
                null)
            .andExpect(status().isOk())
            .andExpect(header().string("Delta-Table-Version", "2"))
            .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void queriesDeltaFilesForDeletionVectorsTable() throws Exception {
    String bearer =
        shareTable("dv-query", "main.sales.deletionvectors", "sales.deletionvectors");
    String endpoint =
        PROTOCOL + "/shares/dv-query/schemas/sales/tables/deletionvectors/query";

    // Delta format signs the DV object and returns deletionVectorFileId for cache keys.
    String delta =
        query(
                endpoint,
                bearer,
                "{}",
                "responseformat=delta;readerfeatures=deletionvectors")
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertDeltaQuery(delta);
    assertTrue(delta.contains("\"deletionVectorFileId\""), delta);
    assertTrue(delta.contains("\"deletionVector\""), delta);
    assertTrue(delta.contains("X-Amz-Signature="), delta);
  }

  @Test
  void rejectsQueryForUngrantedTables() throws Exception {
    createShare("hidden-query");
    addObject("hidden-query", "TABLE", "main.sales.table1", "sales.table1");
    String bearer = shareTable("granted-query", "main.sales.table1", "sales.table1");

    // A table in an ungranted share is 404, not 403.
    query(
            PROTOCOL + "/shares/hidden-query/schemas/sales/tables/table1/query",
            bearer,
            "{}",
            null)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
  }

  private static void assertParquetQuery(String ndjson) {
    assertTrue(ndjson.contains("\"protocol\""), ndjson);
    assertTrue(ndjson.contains("\"metaData\""), ndjson);
    assertTrue(ndjson.contains("\"schemaString\""), ndjson);
    assertTrue(ndjson.contains("\"file\""), ndjson);
    assertTrue(ndjson.contains("\"url\""), ndjson);
    assertFalse(ndjson.contains("\"deltaProtocol\""), ndjson);
    assertFalse(ndjson.contains("\"deltaSingleAction\""), ndjson);
  }

  private static void assertDeltaQuery(String ndjson) {
    assertTrue(ndjson.contains("\"deltaProtocol\""), ndjson);
    assertTrue(ndjson.contains("\"deltaMetadata\""), ndjson);
    assertTrue(ndjson.contains("\"deltaSingleAction\""), ndjson);
  }

  private String shareTable(String share, String catalogName, String sharedAs) throws Exception {
    createShare(share);
    addObject(share, "TABLE", catalogName, sharedAs);
    String bearer = createAndActivateRecipient(share + "-partner");
    grant(share, share + "-partner");
    return bearer;
  }

  private ResultActions query(String endpoint, String bearer, String body, String capabilities)
      throws Exception {
    var request =
        post(endpoint)
            .header("Authorization", "Bearer " + bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    if (capabilities != null) {
      request = request.header("delta-sharing-capabilities", capabilities);
    }
    return mvc.perform(request);
  }
}
