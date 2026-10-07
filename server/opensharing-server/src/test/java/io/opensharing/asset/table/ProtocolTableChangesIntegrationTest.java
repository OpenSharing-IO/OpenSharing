package io.opensharing.asset.table;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opensharing.http.ErrorCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Query Table Changes (Change Data Feed) through the local catalog, Kernel, and real cloud Delta
 * logs. Individual cloud cases are skipped unless their repository credentials are present.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:protocol-table-changes;DB_CLOSE_DELAY=-1",
      "opensharing.test.stub-protocol-dependencies=false"
    })
@TestPropertySource(properties = "opensharing.catalog.local.file=classpath:local-catalog-cloud.yml")
@Timeout(60)
class ProtocolTableChangesIntegrationTest extends ProtocolTableIntegrationSupport {

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void queriesChangeDataFeedOfCdfTable() throws Exception {
    String bearer = shareTable("cdf-changes", "main.sales.cdf", "sales.cdf");
    String endpoint = tableEndpoint("cdf-changes", "sales.cdf") + "/changes";

    // Version 4 turns the Change Data Feed off and version 5 turns it back on.
    String parquet =
        ok(changes(endpoint, bearer, null, "startingVersion", "0", "endingVersion", "3"), "0");
    assertTrue(parquet.contains("\"protocol\""), parquet);
    assertTrue(parquet.contains("\"metaData\""), parquet);
    long changes = count(parquet, "add") + count(parquet, "remove") + count(parquet, "cdf");
    assertTrue(changes > 0, parquet);
    String delta =
        ok(
            changes(
                endpoint,
                bearer,
                "responseformat=delta",
                "startingVersion",
                "0",
                "endingVersion",
                "3"),
            "0");
    assertEquals(changes, count(delta, "file"), delta);

    // A range through version 4 is rejected.
    changes(endpoint, bearer, null, "startingVersion", "0")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE))
        .andExpect(
            jsonPath("$.message").value(containsString("not recorded for version [4]")));
    // An end after the latest version is capped rather than rejected.
    ok(changes(endpoint, bearer, null, "startingVersion", "5", "endingVersion", "1000"), "5");
    // A range that ends before it starts is rejected.
    changes(endpoint, bearer, null, "startingVersion", "1", "endingVersion", "0")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void rejectsChangeDataFeedOfTable1() throws Exception {
    rejectsChangeDataFeed("table1-cdf", "main.sales.table1", "sales.table1");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AZURE_TEST_ACCOUNT_KEY", matches = ".+")
  void rejectsChangeDataFeedOfAzureTable() throws Exception {
    rejectsChangeDataFeed("azure-cdf", "main.sales.azure", "sales.azure");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "GOOGLE_APPLICATION_CREDENTIALS", matches = ".+")
  void rejectsChangeDataFeedOfGoogleTable() throws Exception {
    rejectsChangeDataFeed("gcs-cdf", "main.sales.gcs", "sales.gcs");
  }

  // Change Data Feed is not enabled on table1 or its cloud copies.
  private void rejectsChangeDataFeed(String share, String catalogName, String sharedAs)
      throws Exception {
    String bearer = shareTable(share, catalogName, sharedAs);
    String endpoint = tableEndpoint(share, sharedAs) + "/changes";

    changes(endpoint, bearer, null, "startingVersion", "0")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE))
        .andExpect(jsonPath("$.message").value(containsString("change data was not recorded")));
    // A starting version or timestamp is required.
    changes(endpoint, bearer, null)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
  }

  /** {@code params} alternates query parameter names and values. */
  private ResultActions changes(
      String endpoint, String bearer, String capabilities, String... params) throws Exception {
    var request = get(endpoint).header("Authorization", "Bearer " + bearer);
    if (capabilities != null) {
      request = request.header(DeltaSharingCapabilities.HEADER, capabilities);
    }
    for (int i = 0; i < params.length; i += 2) {
      request = request.param(params[i], params[i + 1]);
    }
    return mvc.perform(request);
  }
}
