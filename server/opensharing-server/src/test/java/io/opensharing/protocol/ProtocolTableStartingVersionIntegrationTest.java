package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linecorp.armeria.common.HttpMethod;
import io.opensharing.TestServer.Response;
import io.opensharing.util.ErrorCodes;
import java.util.Arrays;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Query Table startingVersion and endingVersion on real cloud Delta logs, with presigned file
 * URLs. Individual cloud cases are skipped unless their repository credentials are present.
 */
@Timeout(60)
class ProtocolTableStartingVersionIntegrationTest extends ProtocolApiSupport {

  @BeforeAll
  static void startCloudServer() {
    Properties properties = new Properties();
    properties.setProperty("catalog.local.file", "classpath:local-catalog-cloud.yml");
    restartServer(properties);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void queriesTable1Changes() {
    queriesChanges("table1-changes", "main.sales.table1", "sales.table1", 1, 2);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AZURE_TEST_ACCOUNT_KEY", matches = ".+")
  void queriesAzureTableChanges() {
    queriesChanges("azure-changes", "main.sales.azure", "sales.azure", 0, 0);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "GOOGLE_APPLICATION_CREDENTIALS", matches = ".+")
  void queriesGoogleTableChanges() {
    queriesChanges("gcs-changes", "main.sales.gcs", "sales.gcs", 0, 0);
  }

  private static void queriesChanges(
      String share, String catalogName, String sharedAs, long firstAdds, long latest) {
    createShare(share);
    addObject(share, "TABLE", catalogName, sharedAs);
    String bearer = createAndActivateRecipient(share + "-partner");
    grant(share, share + "-partner");
    String[] alias = sharedAs.split("\\.");
    String endpoint =
        PROTOCOL + "/shares/" + share + "/schemas/" + alias[0] + "/tables/" + alias[1] + "/query";

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
      Response response = query(endpoint, bearer, body, null);
      assertEquals(400, response.status(), body);
      assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, response.errorCode(), body);
    }
  }

  /** Lines whose single key is {@code action}, such as add or file. */
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

  /** Expects 200 with {@code version} as the Delta-Table-Version and returns the NDJSON body. */
  private static String ok(Response response, String version) {
    assertEquals(200, response.status(), response.text());
    assertEquals(version, response.headers().get("Delta-Table-Version"));
    return response.text();
  }
}
