package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Recipient Query Table Version through the local catalog, Kernel, and real cloud Delta logs.
 * Individual cloud cases are skipped unless their repository credentials are present.
 */
@Timeout(60)
class ProtocolTableVersionIntegrationTest extends ProtocolApiSupport {

  @BeforeAll
  static void startCloudServer() {
    Properties properties = new Properties();
    properties.setProperty("catalog.local.file", "classpath:local-catalog-cloud.yml");
    restartServer(properties);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void queriesLatestAndTimestampVersionsForTable1() {
    String bearer = shareTable("table1-version", "main.sales.table1", "sales.table1");
    String endpoint = PROTOCOL + "/shares/table1-version/schemas/sales/tables/table1/version";

    Response latest = version(endpoint, bearer, null);
    assertVersion("2", latest);
    assertTrue(latest.body().isMissingNode());
    assertVersion("0", version(endpoint, bearer, "1970-01-01T00:00:00Z"));
    assertInvalid(version(endpoint, bearer, "2099-01-01T00:00:00Z"));
    assertInvalid(version(endpoint, bearer, "not-a-timestamp"));
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void queriesAtOrAfterTimestampsOnTheStartingTimestampTable() {
    String bearer =
        shareTable("ts-version", "main.sales.startingtimestamp", "sales.startingtimestamp");
    String endpoint =
        PROTOCOL + "/shares/ts-version/schemas/sales/tables/startingtimestamp/version";

    assertVersion("5", version(endpoint, bearer, null));
    assertVersion("0", version(endpoint, bearer, "2023-05-09T08:00:00Z"));
    assertVersion("3", version(endpoint, bearer, "2023-05-09T08:32:55.517Z"));
    assertVersion("4", version(endpoint, bearer, "2023-05-09T08:45:00Z"));
    assertVersion("5", version(endpoint, bearer, "2023-05-09T09:09:58.088Z"));
    assertInvalid(version(endpoint, bearer, "2023-05-09T10:00:00Z"));
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void queriesVersionThroughASchemaGrant() {
    createShare("schema-version");
    addObject("schema-version", "SCHEMA", "main.sales", "sales");
    String bearer = createAndActivateRecipient("schema-version-partner");
    grant("schema-version", "schema-version-partner");

    String tables = PROTOCOL + "/shares/schema-version/schemas/";
    assertVersion("2", version(tables + "SALES/tables/TABLE1/version", bearer, null));
    assertVersion(
        "2",
        version(
            tables + "sales/tables/startingtimestamp/version", bearer, "2023-05-09T08:32:00Z"));
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AZURE_TEST_ACCOUNT_KEY", matches = ".+")
  void queriesAzureTableVersions() {
    queryCloudTableVersions("azure-version", "main.sales.azure", "sales.azure");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "GOOGLE_APPLICATION_CREDENTIALS", matches = ".+")
  void queriesGoogleTableVersions() {
    queryCloudTableVersions("gcs-version", "main.sales.gcs", "sales.gcs");
  }

  private static String shareTable(String share, String catalogName, String sharedAs) {
    createShare(share);
    addObject(share, "TABLE", catalogName, sharedAs);
    String bearer = createAndActivateRecipient(share + "-partner");
    grant(share, share + "-partner");
    return bearer;
  }

  private static void queryCloudTableVersions(String share, String catalogName, String sharedAs) {
    String bearer = shareTable(share, catalogName, sharedAs);
    String[] alias = sharedAs.split("\\.");
    String endpoint =
        PROTOCOL + "/shares/" + share + "/schemas/" + alias[0] + "/tables/" + alias[1] + "/version";

    Response latest = version(endpoint, bearer, null);
    assertEquals(200, latest.status());
    String header = latest.headers().get("Delta-Table-Version");
    assertTrue(header != null && Long.parseLong(header) >= 0, "latest version was " + header);
    assertVersion("0", version(endpoint, bearer, "1970-01-01T00:00:00Z"));
    assertInvalid(version(endpoint, bearer, "2099-01-01T00:00:00Z"));
  }

  private static Response version(String path, String bearer, String startingTimestamp) {
    String query =
        startingTimestamp == null
            ? ""
            : "?startingTimestamp=" + URLEncoder.encode(startingTimestamp, StandardCharsets.UTF_8);
    return server.get(path + query, bearer);
  }

  private static void assertVersion(String expected, Response response) {
    assertEquals(200, response.status());
    assertEquals(expected, response.headers().get("Delta-Table-Version"));
  }

  private static void assertInvalid(Response response) {
    assertEquals(400, response.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, response.errorCode());
  }
}
