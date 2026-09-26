package io.opensharing.asset.table;

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

    String latest =
        metadata(endpoint, bearer, null, null)
            .andExpect(status().isOk())
            .andExpect(header().string("Delta-Table-Version", "2"))
            .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertTrue(latest.contains("\"protocol\""), latest);
    assertTrue(latest.contains("\"metaData\""), latest);
    assertTrue(latest.contains("eventTime") || latest.contains("date"), latest);

    String delta =
        metadata(endpoint, bearer, null, null, "responseformat=delta")
            .andExpect(status().isOk())
            .andExpect(header().string("Delta-Table-Version", "2"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertTrue(delta.contains("\"deltaProtocol\""), delta);
    assertTrue(delta.contains("\"deltaMetadata\""), delta);

    metadata(endpoint, bearer, 0L, null)
        .andExpect(status().isOk())
        .andExpect(header().string("Delta-Table-Version", "0"));
    metadata(endpoint, bearer, null, "1970-01-01T00:00:00Z")
        .andExpect(status().isOk())
        .andExpect(header().string("Delta-Table-Version", "0"));
    metadata(endpoint, bearer, 1L, "1970-01-01T00:00:00Z")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void queriesMetadataThroughASchemaGrant() throws Exception {
    createShare("schema-metadata");
    addObject("schema-metadata", "SCHEMA", "main.sales", "sales");
    String bearer = createAndActivateRecipient("schema-metadata-partner");
    grant("schema-metadata", "schema-metadata-partner");

    metadata(
            PROTOCOL + "/shares/schema-metadata/schemas/SALES/tables/TABLE1/metadata",
            bearer,
            null,
            null)
        .andExpect(status().isOk())
        .andExpect(header().string("Delta-Table-Version", "2"));
  }

  @Test
  void rejectsMetadataForUngrantedTables() throws Exception {
    createShare("hidden-metadata");
    addObject("hidden-metadata", "TABLE", "main.sales.table1", "sales.table1");
    String bearer = shareTable("granted-metadata", "main.sales.table1", "sales.table1");

    metadata(
            PROTOCOL + "/shares/hidden-metadata/schemas/sales/tables/table1/metadata",
            bearer,
            null,
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
