package io.opensharing.asset.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opensharing.http.ErrorCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Query Table startingVersion and endingVersion through the local catalog, Kernel, and real cloud
 * Delta logs. Individual cloud cases are skipped unless their repository credentials are present.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:protocol-table-starting-version;DB_CLOSE_DELAY=-1",
      "opensharing.test.stub-protocol-dependencies=false"
    })
@TestPropertySource(properties = "opensharing.catalog.local.file=classpath:local-catalog-cloud.yml")
@Timeout(60)
class ProtocolTableStartingVersionIntegrationTest extends ProtocolTableIntegrationSupport {

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void queriesTable1Changes() throws Exception {
    queriesChanges("table1-changes", "main.sales.table1", "sales.table1", 1, 2);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AZURE_TEST_ACCOUNT_KEY", matches = ".+")
  void queriesAzureTableChanges() throws Exception {
    queriesChanges("azure-changes", "main.sales.azure", "sales.azure", 0, 0);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "GOOGLE_APPLICATION_CREDENTIALS", matches = ".+")
  void queriesGoogleTableChanges() throws Exception {
    queriesChanges("gcs-changes", "main.sales.gcs", "sales.gcs", 0, 0);
  }

  private void queriesChanges(
      String share, String catalogName, String sharedAs, long firstAdds, long latest)
      throws Exception {
    String bearer = shareTable(share, catalogName, sharedAs);
    String endpoint = tableEndpoint(share, sharedAs) + "/query";

    // Each file added at firstAdds comes back as an add with its version.
    String range = "{\"startingVersion\":" + firstAdds + ",\"endingVersion\":" + firstAdds + "}";
    String parquet = ok(query(endpoint, bearer, range, null), "" + firstAdds);
    assertTrue(count(parquet, "add") > 0, parquet);
    assertEquals(0, count(parquet, "file"), parquet);
    assertTrue(parquet.contains("\"version\":" + firstAdds), parquet);
    String delta = ok(query(endpoint, bearer, range, "responseformat=delta"), "" + firstAdds);
    assertEquals(count(parquet, "add"), count(delta, "file"), delta);
    assertTrue(delta.contains("\"add\""), delta);
    // Without endingVersion the range runs through the latest version.
    ok(query(endpoint, bearer, "{\"startingVersion\":" + latest + "}", null), "" + latest);

    // Versions after the latest, and a range that ends before it starts, are rejected.
    for (String body :
        new String[] {
          "{\"startingVersion\":" + (latest + 1) + "}",
          "{\"startingVersion\":0,\"endingVersion\":" + (latest + 1) + "}",
          "{\"startingVersion\":1,\"endingVersion\":0}"
        }) {
      query(endpoint, bearer, body, null)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
    }
  }
}
