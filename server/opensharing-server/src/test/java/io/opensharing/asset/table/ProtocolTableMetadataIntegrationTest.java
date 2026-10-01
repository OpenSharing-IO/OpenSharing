package io.opensharing.asset.table;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Recipient Query Table Metadata through the local catalog, Kernel, and real cloud Delta logs.
 * Individual cloud cases are skipped unless their repository credentials are present.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:protocol-table-metadata;DB_CLOSE_DELAY=-1",
      "opensharing.test.stub-protocol-dependencies=false"
    })
@TestPropertySource(properties = "opensharing.catalog.local.file=classpath:local-catalog-cloud.yml")
@Timeout(60)
class ProtocolTableMetadataIntegrationTest extends ProtocolApiSupport {

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void queriesLatestAndHistoricalMetadataForTable1() throws Exception {
    String bearer = shareTable("table1-metadata", "main.sales.table1", "sales.table1");
    String endpoint = PROTOCOL + "/shares/table1-metadata/schemas/sales/tables/table1/metadata";

    // Latest metadata defaults to parquet NDJSON at table1 version 2.
    String latest =
        metadata(endpoint, bearer, null, null)
            .andExpect(status().isOk())
            .andExpect(header().string("Delta-Table-Version", "2"))
            .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertParquetMetadata(latest);
    assertTrue(latest.contains("eventTime") || latest.contains("date"), latest);

    // Explicit parquet keeps protocol + schemaString, not delta wrappers.
    assertParquetMetadata(
        metadata(endpoint, bearer, null, null, "responseformat=parquet")
            .andExpect(status().isOk())
            .andExpect(header().string("Delta-Table-Version", "2"))
            .andReturn()
            .getResponse()
            .getContentAsString());
    // Dual responseformat prefers delta over parquet.
    assertDeltaMetadata(
        metadata(endpoint, bearer, null, null, "responseformat=delta,parquet")
            .andExpect(status().isOk())
            .andExpect(header().string("Delta-Table-Version", "2"))
            .andReturn()
            .getResponse()
            .getContentAsString());
    // Delta-only wraps Kernel protocol and metadata.
    assertDeltaMetadata(
        metadata(endpoint, bearer, null, null, "responseformat=delta")
            .andExpect(status().isOk())
            .andExpect(header().string("Delta-Table-Version", "2"))
            .andReturn()
            .getResponse()
            .getContentAsString());

    // Historical version=0 returns that snapshot.
    metadata(endpoint, bearer, 0L, null)
        .andExpect(status().isOk())
        .andExpect(header().string("Delta-Table-Version", "0"));
    // As-of the first commit time is also version 0.
    metadata(endpoint, bearer, null, "2021-05-04T17:24:06Z")
        .andExpect(status().isOk())
        .andExpect(header().string("Delta-Table-Version", "0"));
    // As-of before version 0 is INVALID_PARAMETER_VALUE.
    metadata(endpoint, bearer, null, "1970-01-01T00:00:00Z")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
    // version and timestamp together are mutually exclusive.
    metadata(endpoint, bearer, 1L, "2021-05-04T17:24:06Z")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void queriesDeltaMetadataForDeletionVectorsTable() throws Exception {
    String bearer =
        shareTable("dv-metadata", "main.sales.deletionvectors", "sales.deletionvectors");
    String endpoint =
        PROTOCOL + "/shares/dv-metadata/schemas/sales/tables/deletionvectors/metadata";

    // Deletion-vector table metadata in delta format includes the deletionVectors feature.
    String delta =
        metadata(endpoint, bearer, null, null, "responseformat=delta")
            .andExpect(status().isOk())
            .andExpect(header().exists("Delta-Table-Version"))
            .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertDeltaMetadata(delta);
    assertTrue(delta.contains("deletionVectors"), delta);

    // Dual responseformat still prefers delta for a reader-v3 table.
    String both =
        metadata(endpoint, bearer, null, null, "responseformat=delta,parquet")
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertDeltaMetadata(both);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void queriesMetadataThroughASchemaGrant() throws Exception {
    createShare("schema-metadata");
    addObject("schema-metadata", "SCHEMA", "main.sales", "sales");
    String bearer = createAndActivateRecipient("schema-metadata-partner");
    grant("schema-metadata", "schema-metadata-partner");

    // A SCHEMA grant can Query Table Metadata for catalog children (table1).
    assertParquetMetadata(
        metadata(
                PROTOCOL + "/shares/schema-metadata/schemas/SALES/tables/TABLE1/metadata",
                bearer,
                null,
                null)
            .andExpect(status().isOk())
            .andExpect(header().string("Delta-Table-Version", "2"))
            .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  @Test
  void rejectsMetadataForUngrantedTables() throws Exception {
    createShare("hidden-metadata");
    addObject("hidden-metadata", "TABLE", "main.sales.table1", "sales.table1");
    String bearer = shareTable("granted-metadata", "main.sales.table1", "sales.table1");

    // A table in an ungranted share is 404, not 403.
    metadata(
            PROTOCOL + "/shares/hidden-metadata/schemas/sales/tables/table1/metadata",
            bearer,
            null,
            null)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
  }

  private static void assertParquetMetadata(String ndjson) {
    assertTrue(ndjson.contains("\"protocol\""), ndjson);
    assertTrue(ndjson.contains("\"metaData\""), ndjson);
    assertTrue(ndjson.contains("\"schemaString\""), ndjson);
    assertFalse(ndjson.contains("\"deltaProtocol\""), ndjson);
    assertFalse(ndjson.contains("\"deltaMetadata\""), ndjson);
  }

  private static void assertDeltaMetadata(String ndjson) {
    assertTrue(ndjson.contains("\"deltaProtocol\""), ndjson);
    assertTrue(ndjson.contains("\"deltaMetadata\""), ndjson);
  }

  private String shareTable(String share, String catalogName, String sharedAs) throws Exception {
    createShare(share);
    addObject(share, "TABLE", catalogName, sharedAs);
    String bearer = createAndActivateRecipient(share + "-partner");
    grant(share, share + "-partner");
    return bearer;
  }

  private ResultActions metadata(String endpoint, String bearer, Long version, String timestamp)
      throws Exception {
    return metadata(endpoint, bearer, version, timestamp, null);
  }

  private ResultActions metadata(
      String endpoint, String bearer, Long version, String timestamp, String capabilities)
      throws Exception {
    var request = get(endpoint).header("Authorization", "Bearer " + bearer);
    if (capabilities != null) {
      request = request.header("delta-sharing-capabilities", capabilities);
    }
    if (version != null) {
      request = request.param("version", Long.toString(version));
    }
    if (timestamp != null) {
      request = request.param("timestamp", timestamp);
    }
    return mvc.perform(request);
  }
}
