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
 * Query Table Changes on real cloud Delta logs, with presigned file URLs. Individual cloud cases
 * are skipped unless their repository credentials are present.
 */
@Timeout(60)
class ProtocolTableChangesIntegrationTest extends ProtocolApiSupport {

  @BeforeAll
  static void startCloudServer() {
    Properties properties = new Properties();
    properties.setProperty("catalog.local.file", "classpath:local-catalog-cloud.yml");
    restartServer(properties);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void queriesChangeDataFeedOfCdfTable() {
    String bearer = shareTable("cdf-changes", "main.sales.cdf", "sales.cdf");
    String endpoint = endpoint("cdf-changes", "sales.cdf");

    // Version 4 turns the Change Data Feed off and version 5 turns it back on.
    String range = "?startingVersion=0&endingVersion=3";
    String parquet = ok(changes(endpoint + range, bearer, null), "0");
    assertTrue(parquet.contains("\"protocol\""), parquet);
    assertTrue(parquet.contains("\"metaData\""), parquet);
    long changes = count(parquet, "add") + count(parquet, "remove") + count(parquet, "cdf");
    assertTrue(changes > 0, parquet);
    String delta = ok(changes(endpoint + range, bearer, "responseformat=delta"), "0");
    assertEquals(changes, count(delta, "file"), delta);

    // Version 4 stops recording change data after the response has begun, so its error ends it.
    String off =
        ok(changes(endpoint + "?startingVersion=0", bearer, "includeendstreamaction=true"), "0");
    String last = off.substring(off.stripTrailing().lastIndexOf('\n') + 1);
    assertTrue(last.startsWith("{\"endStreamAction\":{\"errorMessage\":"), last);
    assertTrue(last.contains("not recorded for version [4]"), last);
    // An end after the latest version is capped rather than rejected.
    ok(changes(endpoint + "?startingVersion=5&endingVersion=1000", bearer, null), "5");
    // A range that ends before it starts is rejected.
    assertInvalid(changes(endpoint + "?startingVersion=1&endingVersion=0", bearer, null));
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void rejectsChangeDataFeedOfTable1() {
    rejectsChangeDataFeed("table1-cdf", "main.sales.table1", "sales.table1");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AZURE_TEST_ACCOUNT_KEY", matches = ".+")
  void rejectsChangeDataFeedOfAzureTable() {
    rejectsChangeDataFeed("azure-cdf", "main.sales.azure", "sales.azure");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "GOOGLE_APPLICATION_CREDENTIALS", matches = ".+")
  void rejectsChangeDataFeedOfGoogleTable() {
    rejectsChangeDataFeed("gcs-cdf", "main.sales.gcs", "sales.gcs");
  }

  // Change Data Feed is not enabled on table1 or its cloud copies.
  private static void rejectsChangeDataFeed(String share, String catalogName, String sharedAs) {
    String bearer = shareTable(share, catalogName, sharedAs);
    String endpoint = endpoint(share, sharedAs);

    Response unrecorded = changes(endpoint + "?startingVersion=0", bearer, null);
    assertInvalid(unrecorded);
    assertTrue(unrecorded.text().contains("change data was not recorded"), unrecorded.text());
    // A starting version or timestamp is required.
    assertInvalid(changes(endpoint, bearer, null));
  }

  private static String shareTable(String share, String catalogName, String sharedAs) {
    createShare(share);
    addObject(share, "TABLE", catalogName, sharedAs);
    String bearer = createAndActivateRecipient(share + "-partner");
    grant(share, share + "-partner");
    return bearer;
  }

  /** The changes endpoint of {@code sharedAs}, which is {@code schema.table}. */
  private static String endpoint(String share, String sharedAs) {
    String[] alias = sharedAs.split("\\.");
    return PROTOCOL + "/shares/" + share + "/schemas/" + alias[0] + "/tables/" + alias[1]
        + "/changes";
  }

  private static Response changes(String path, String bearer, String capabilities) {
    return capabilities == null
        ? server.get(path, bearer)
        : server.call(
            HttpMethod.GET, path, bearer, null, "delta-sharing-capabilities", capabilities);
  }

  /** Lines whose single key is {@code action}, such as add, remove, cdf, or file. */
  private static long count(String ndjson, String action) {
    return Arrays.stream(ndjson.split("\n"))
        .filter(line -> line.startsWith("{\"" + action + "\""))
        .count();
  }

  /** Expects 200 with {@code version} as the Delta-Table-Version and returns the NDJSON body. */
  private static String ok(Response response, String version) {
    assertEquals(200, response.status(), response.text());
    assertEquals(version, response.headers().get("Delta-Table-Version"));
    return response.text();
  }

  private static void assertInvalid(Response response) {
    assertEquals(400, response.status(), response.text());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, response.errorCode());
  }
}
