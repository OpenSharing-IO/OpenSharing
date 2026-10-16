package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.TestServer.Response;
import java.util.Arrays;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Query Table predicate and limit hints on real cloud Delta logs. Individual cloud cases are
 * skipped unless their repository credentials are present.
 */
@Timeout(60)
class ProtocolTablePushdownIntegrationTest extends ProtocolApiSupport {

  @BeforeAll
  static void startCloudServer() {
    Properties properties = new Properties();
    properties.setProperty("catalog.local.file", "classpath:local-catalog-cloud.yml");
    restartServer(properties);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void limitsTable1Files() {
    String bearer = shareTable("table1-hints", "main.sales.table1", "sales.table1");
    String endpoint = endpoint("table1-hints", "sales.table1");

    long all = files(endpoint, bearer, "{}", "2");
    assertTrue(all > 0, "table1 has no files");
    // The limit is checked before each file, so at least one file is returned.
    long limited = files(endpoint, bearer, "{\"limitHint\":1}", "2");
    assertTrue(limited >= 1 && limited <= all, limited + " of " + all);
    // A hint that does not parse, or names a missing column, returns every file.
    assertEquals(all, files(endpoint, bearer, "{\"jsonPredicateHints\":\"not json\"}", "2"));
    assertEquals(all, files(endpoint, bearer, equalHint("missing", "x"), "2"));
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AZURE_TEST_ACCOUNT_KEY", matches = ".+")
  void filtersAzureTableFiles() {
    filtersFiles("azure-hints", "main.sales.azure", "sales.azure");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "GOOGLE_APPLICATION_CREDENTIALS", matches = ".+")
  void filtersGoogleTableFiles() {
    filtersFiles("gcs-hints", "main.sales.gcs", "sales.gcs");
  }

  // The cloud copies of table1 have one file, in partition c2=foo bar, holding one c1=foo bar row.
  private static void filtersFiles(String share, String catalogName, String sharedAs) {
    String bearer = shareTable(share, catalogName, sharedAs);
    String endpoint = endpoint(share, sharedAs);

    assertEquals(1, files(endpoint, bearer, equalHint("c2", "foo bar"), "0"));
    // A partition value no file has prunes the file.
    assertEquals(0, files(endpoint, bearer, equalHint("c2", "baz"), "0"));
    // c1 is not a partition column, so its min/max stats skip the file.
    assertEquals(0, files(endpoint, bearer, equalHint("c1", "zzz"), "0"));
    // A hint that does not parse returns every file.
    assertEquals(1, files(endpoint, bearer, "{\"jsonPredicateHints\":\"not json\"}", "0"));
    // The limit is checked before each file, so the first file is always returned.
    assertEquals(1, files(endpoint, bearer, "{\"limitHint\":1}", "0"));
  }

  private static String shareTable(String share, String catalogName, String sharedAs) {
    createShare(share);
    addObject(share, "TABLE", catalogName, sharedAs);
    String bearer = createAndActivateRecipient(share + "-partner");
    grant(share, share + "-partner");
    return bearer;
  }

  /** The query endpoint of {@code sharedAs}, which is {@code schema.table}. */
  private static String endpoint(String share, String sharedAs) {
    String[] alias = sharedAs.split("\\.");
    return PROTOCOL + "/shares/" + share + "/schemas/" + alias[0] + "/tables/" + alias[1]
        + "/query";
  }

  private static long files(String endpoint, String bearer, String body, String version) {
    Response response = server.post(endpoint, bearer, body);
    assertEquals(200, response.status(), response.text());
    assertEquals(version, response.headers().get("Delta-Table-Version"));
    return Arrays.stream(response.text().split("\n"))
        .filter(line -> line.startsWith("{\"file\""))
        .count();
  }

  /** A request body whose jsonPredicateHints is {@code column = value} on string values. */
  private static String equalHint(String column, String value) {
    String hint =
        "{\"op\":\"equal\",\"children\":["
            + "{\"op\":\"column\",\"name\":\"" + column + "\",\"valueType\":\"string\"},"
            + "{\"op\":\"literal\",\"value\":\"" + value + "\",\"valueType\":\"string\"}]}";
    return "{\"jsonPredicateHints\":\"" + hint.replace("\"", "\\\"") + "\"}";
  }
}
