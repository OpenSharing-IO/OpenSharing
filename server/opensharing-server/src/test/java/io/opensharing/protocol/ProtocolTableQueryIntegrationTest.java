package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import io.opensharing.TestServer.Response;
import io.opensharing.util.ErrorCodes;
import java.util.Arrays;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Recipient Query Table through the local catalog, Kernel, real cloud Delta logs, and presigned
 * file URLs. Individual cloud cases are skipped unless their repository credentials are present.
 */
@Timeout(60)
class ProtocolTableQueryIntegrationTest extends ProtocolApiSupport {

  @BeforeAll
  static void startCloudServer() {
    Properties properties = new Properties();
    properties.setProperty("catalog.local.file", "classpath:local-catalog-cloud.yml");
    restartServer(properties);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void queriesLatestAndHistoricalFilesForTable1() {
    String bearer = shareTable("table1-query", "main.sales.table1", "sales.table1");
    String endpoint = PROTOCOL + "/shares/table1-query/schemas/sales/tables/table1/query";

    // Latest snapshot defaults to parquet NDJSON at table1 version 2.
    Response latest = query(endpoint, bearer, "{}", null);
    assertVersion("2", latest);
    assertTrue(latest.headers().contentType().is(MediaType.parse("application/x-ndjson")));
    assertParquetQuery(latest.text());
    assertTrue(latest.text().contains("https://"));
    assertTrue(latest.text().contains("X-Amz-Signature="));

    // Explicit parquet keeps protocol, schemaString, and file.url, not delta wrappers.
    Response parquet = query(endpoint, bearer, "{}", "responseformat=parquet");
    assertVersion("2", parquet);
    assertParquetQuery(parquet.text());
    // A reader-v1 table answers in parquet when the client accepts it.
    Response both = query(endpoint, bearer, "{}", "responseformat=delta,parquet");
    assertVersion("2", both);
    assertParquetQuery(both.text());
    // Delta-only wraps Kernel add actions as deltaSingleAction.
    Response delta = query(endpoint, bearer, "{}", "responseformat=delta");
    assertVersion("2", delta);
    assertDeltaQuery(delta.text());

    // Historical version 0, and as of the first commit time, return that snapshot.
    assertVersion("0", query(endpoint, bearer, "{\"version\":0}", null));
    assertVersion(
        "0", query(endpoint, bearer, "{\"timestamp\":\"2021-05-04T17:24:06Z\"}", null));
    // As of before version 0 is INVALID_PARAMETER_VALUE.
    assertInvalid(query(endpoint, bearer, "{\"timestamp\":\"1970-01-01T00:00:00Z\"}", null));
    // version and timestamp together are mutually exclusive.
    assertInvalid(
        query(endpoint, bearer, "{\"version\":1,\"timestamp\":\"2021-05-04T17:24:06Z\"}", null));
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void queriesFilesThroughASchemaGrant() {
    createShare("schema-query");
    addObject("schema-query", "SCHEMA", "main.sales", "sales");
    String bearer = createAndActivateRecipient("schema-query-partner");
    grant("schema-query", "schema-query-partner");

    // A SCHEMA grant can Query Table for catalog children (table1).
    Response response =
        query(
            PROTOCOL + "/shares/schema-query/schemas/SALES/tables/TABLE1/query",
            bearer,
            "{}",
            null);
    assertVersion("2", response);
    assertParquetQuery(response.text());
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void queriesDeltaFilesForDeletionVectorsTable() {
    String bearer = shareTable("dv-query", "main.sales.deletionvectors", "sales.deletionvectors");
    String endpoint = PROTOCOL + "/shares/dv-query/schemas/sales/tables/deletionvectors/query";

    // Delta format signs the deletion vector object and returns its file id for cache keys.
    Response delta =
        query(endpoint, bearer, "{}", "responseformat=delta;readerfeatures=deletionvectors");
    assertEquals(200, delta.status());
    assertDeltaQuery(delta.text());
    assertTrue(delta.text().contains("\"deletionVectorFileId\""));
    assertTrue(delta.text().contains("\"deletionVector\""));
    assertTrue(delta.text().contains("X-Amz-Signature="));

    // A client that accepts delta must advertise the reader feature.
    assertInvalid(query(endpoint, bearer, "{}", "responseformat=delta"));
    // A parquet client gets delta for a reader-v3 table and decides whether it can read it.
    Response parquet = query(endpoint, bearer, "{}", null);
    assertEquals(200, parquet.status());
    assertEquals("responseformat=delta", parquet.headers().get("delta-sharing-capabilities"));
    assertDeltaQuery(parquet.text());
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AZURE_TEST_ACCOUNT_KEY", matches = ".+")
  void queriesAzureTableFiles() {
    queryCloudTableFiles("azure-query", "main.sales.azure", "sales.azure", "sig=");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "GOOGLE_APPLICATION_CREDENTIALS", matches = ".+")
  void queriesGoogleTableFiles() {
    queryCloudTableFiles("gcs-query", "main.sales.gcs", "sales.gcs", "access_token=");
  }

  // The cloud copies of table1 have one commit, version 0, with one file in partition c2=foo bar.
  private static void queryCloudTableFiles(
      String share, String catalogName, String sharedAs, String signature) {
    String bearer = shareTable(share, catalogName, sharedAs);
    String[] alias = sharedAs.split("\\.");
    String endpoint =
        PROTOCOL + "/shares/" + share + "/schemas/" + alias[0] + "/tables/" + alias[1] + "/query";

    Response latest = query(endpoint, bearer, "{}", null);
    assertVersion("0", latest);
    assertParquetQuery(latest.text());
    assertEquals(1, count(latest.text(), "file"));
    assertTrue(latest.text().contains(signature));
    Response delta = query(endpoint, bearer, "{}", "responseformat=delta");
    assertVersion("0", delta);
    assertDeltaQuery(delta.text());

    // A historical query reports the version on metaData and on every file.
    Response historical = query(endpoint, bearer, "{\"version\":0}", null);
    assertVersion("0", historical);
    assertEquals(
        2, historical.text().split("\"version\":0", -1).length - 1);
    // A version after the latest is INVALID_PARAMETER_VALUE.
    assertInvalid(query(endpoint, bearer, "{\"version\":1}", null));
  }

  private static void assertParquetQuery(String ndjson) {
    assertTrue(ndjson.contains("\"protocol\""));
    assertTrue(ndjson.contains("\"metaData\""));
    assertTrue(ndjson.contains("\"schemaString\""));
    assertTrue(ndjson.contains("\"file\""));
    assertTrue(ndjson.contains("\"url\""));
    assertFalse(ndjson.contains("\"deltaProtocol\""));
    assertFalse(ndjson.contains("\"deltaSingleAction\""));
  }

  private static void assertDeltaQuery(String ndjson) {
    assertTrue(ndjson.contains("\"deltaProtocol\""));
    assertTrue(ndjson.contains("\"deltaMetadata\""));
    assertTrue(ndjson.contains("\"deltaSingleAction\""));
  }

  /** Lines whose single key is {@code action}, such as file. */
  private static long count(String ndjson, String action) {
    return Arrays.stream(ndjson.split("\n"))
        .filter(line -> line.startsWith("{\"" + action + "\""))
        .count();
  }

  private static String shareTable(String share, String catalogName, String sharedAs) {
    createShare(share);
    addObject(share, "TABLE", catalogName, sharedAs);
    String bearer = createAndActivateRecipient(share + "-partner");
    grant(share, share + "-partner");
    return bearer;
  }

  private static Response query(String path, String bearer, String body, String capabilities) {
    return capabilities == null
        ? server.post(path, bearer, body)
        : server.call(
            HttpMethod.POST, path, bearer, body, "delta-sharing-capabilities", capabilities);
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
