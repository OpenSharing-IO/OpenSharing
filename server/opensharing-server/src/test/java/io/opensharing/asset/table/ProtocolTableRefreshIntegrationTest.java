package io.opensharing.asset.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opensharing.http.ErrorCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Query Table refresh tokens and endStreamAction through the local catalog, Kernel, and real cloud
 * Delta logs. Individual cloud cases are skipped unless their repository credentials are present.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:protocol-table-refresh;DB_CLOSE_DELAY=-1",
      "opensharing.test.stub-protocol-dependencies=false"
    })
@TestPropertySource(properties = "opensharing.catalog.local.file=classpath:local-catalog-cloud.yml")
@Timeout(60)
class ProtocolTableRefreshIntegrationTest extends ProtocolTableIntegrationSupport {

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void refreshesTable1Query() throws Exception {
    refreshesQuery("table1-refresh", "main.sales.table1", "sales.table1", "2");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AZURE_TEST_ACCOUNT_KEY", matches = ".+")
  void refreshesAzureTableQuery() throws Exception {
    refreshesQuery("azure-refresh", "main.sales.azure", "sales.azure", "0");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "GOOGLE_APPLICATION_CREDENTIALS", matches = ".+")
  void refreshesGoogleTableQuery() throws Exception {
    refreshesQuery("gcs-refresh", "main.sales.gcs", "sales.gcs", "0");
  }

  private void refreshesQuery(String share, String catalogName, String sharedAs, String latest)
      throws Exception {
    String bearer = shareTable(share, catalogName, sharedAs);
    String endpoint = tableEndpoint(share, sharedAs) + "/query";

    // includeRefreshToken ends the response with an endStreamAction that holds the token.
    String first = ok(query(endpoint, bearer, "{\"includeRefreshToken\":true}", null), latest);
    assertEquals(1, count(first, "endStreamAction"), first);
    String endStream = first.substring(first.lastIndexOf("{\"endStreamAction\""));
    assertTrue(endStream.contains("\"minUrlExpirationTimestamp\""), endStream);
    String token = JsonPath.read(endStream, "$.endStreamAction.refreshToken");

    // The token re-reads the snapshot it was issued for.
    String refreshed = ok(query(endpoint, bearer, refreshBody(token), null), latest);
    assertEquals(count(first, "file"), count(refreshed, "file"), refreshed);
    // A token cannot be combined with a version.
    query(endpoint, bearer, "{\"version\":0,\"refreshToken\":\"" + token + "\"}", null)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
    // Nor can a new token be requested for a specific version.
    query(endpoint, bearer, "{\"version\":0,\"includeRefreshToken\":true}", null)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
    // A token that does not decode is rejected.
    query(endpoint, bearer, refreshBody("not-a-token"), null)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));

    // includeendstreamaction adds an endStreamAction without a token and is echoed back.
    String withEndStream =
        query(endpoint, bearer, "{}", "responseformat=delta;includeendstreamaction=true")
            .andExpect(status().isOk())
            .andExpect(
                header()
                    .string(
                        DeltaSharingCapabilities.HEADER,
                        "responseformat=delta;includeendstreamaction=true"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertEquals(1, count(withEndStream, "endStreamAction"), withEndStream);
    assertFalse(withEndStream.contains("\"refreshToken\""), withEndStream);
  }

  private static String refreshBody(String token) {
    return "{\"refreshToken\":\"" + token + "\"}";
  }
}
