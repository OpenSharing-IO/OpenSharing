package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpMethod;
import io.opensharing.TestServer.Response;
import java.util.Arrays;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Query Table refresh tokens and endStreamAction on real cloud Delta logs, with presigned file
 * URLs. Individual cloud cases are skipped unless their repository credentials are present.
 */
@Timeout(60)
class ProtocolTableRefreshIntegrationTest extends ProtocolApiSupport {

  private static final ObjectMapper JSON = new ObjectMapper();

  @BeforeAll
  static void startCloudServer() {
    Properties properties = new Properties();
    properties.setProperty("catalog.local.file", "classpath:local-catalog-cloud.yml");
    restartServer(properties);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void refreshesTable1Query() {
    refreshesQuery("table1-refresh", "main.sales.table1", "sales.table1", "2");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AZURE_TEST_ACCOUNT_KEY", matches = ".+")
  void refreshesAzureTableQuery() {
    refreshesQuery("azure-refresh", "main.sales.azure", "sales.azure", "0");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "GOOGLE_APPLICATION_CREDENTIALS", matches = ".+")
  void refreshesGoogleTableQuery() {
    refreshesQuery("gcs-refresh", "main.sales.gcs", "sales.gcs", "0");
  }

  private static void refreshesQuery(
      String share, String catalogName, String sharedAs, String latest) {
    createShare(share);
    addObject(share, "TABLE", catalogName, sharedAs);
    String bearer = createAndActivateRecipient(share + "-partner");
    grant(share, share + "-partner");
    String[] alias = sharedAs.split("\\.");
    String endpoint =
        PROTOCOL + "/shares/" + share + "/schemas/" + alias[0] + "/tables/" + alias[1] + "/query";

    // includeRefreshToken ends the response with an endStreamAction that holds the token.
    Response first = query(endpoint, bearer, "{\"includeRefreshToken\":true}", null);
    assertVersion(latest, first);
    assertEquals(1, count(first.text(), "endStreamAction"), first.text());
    String[] lines = first.text().split("\n");
    JsonNode endStream = readTree(lines[lines.length - 1]).path("endStreamAction");
    assertTrue(endStream.path("minUrlExpirationTimestamp").isNumber(), first.text());
    String token = endStream.path("refreshToken").asText();

    // The token re-reads the snapshot it was issued for.
    Response refreshed = query(endpoint, bearer, "{\"refreshToken\":\"" + token + "\"}", null);
    assertVersion(latest, refreshed);
    assertEquals(count(first.text(), "file"), count(refreshed.text(), "file"), refreshed.text());

    // includeendstreamaction adds an endStreamAction without a token and is echoed back.
    Response withEndStream =
        query(endpoint, bearer, "{}", "responseformat=delta;includeendstreamaction=true");
    assertVersion(latest, withEndStream);
    assertEquals(
        "responseformat=delta;includeendstreamaction=true",
        withEndStream.headers().get("delta-sharing-capabilities"));
    assertEquals(1, count(withEndStream.text(), "endStreamAction"), withEndStream.text());
    assertFalse(withEndStream.text().contains("\"refreshToken\""), withEndStream.text());
  }

  private static JsonNode readTree(String line) {
    try {
      return JSON.readTree(line);
    } catch (JsonProcessingException e) {
      throw new AssertionError("not an NDJSON line: " + line, e);
    }
  }

  /** Lines whose single key is {@code action}, such as file or endStreamAction. */
  private static long count(String ndjson, String action) {
    return Arrays.stream(ndjson.split("\n"))
        .filter(line -> line.startsWith("{\"" + action + "\""))
        .count();
  }

  private static Response query(String path, String bearer, String body, String capabilities) {
    return capabilities == null
        ? server.post(path, bearer, body)
        : server.call(
            HttpMethod.POST, path, bearer, body, "delta-sharing-capabilities", capabilities);
  }

  private static void assertVersion(String expected, Response response) {
    assertEquals(200, response.status(), response.text());
    assertEquals(expected, response.headers().get("Delta-Table-Version"));
  }
}
