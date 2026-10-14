package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Recipient Query Table Metadata through the local catalog, Kernel, and real cloud Delta logs.
 * Individual cloud cases are skipped unless their repository credentials are present.
 */
@Timeout(60)
class ProtocolTableMetadataIntegrationTest extends ProtocolApiSupport {

  @BeforeAll
  static void startCloudServer() {
    Properties properties = new Properties();
    properties.setProperty("catalog.local.file", "classpath:local-catalog-cloud.yml");
    restartServer(properties);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void queriesLatestAndHistoricalMetadataForTable1() {
    String bearer = shareTable("table1-metadata", "main.sales.table1", "sales.table1");
    String endpoint = PROTOCOL + "/shares/table1-metadata/schemas/sales/tables/table1/metadata";

    // Latest metadata defaults to parquet NDJSON at table1 version 2.
    Response latest = metadata(endpoint, bearer, null);
    assertVersion("2", latest);
    assertTrue(latest.headers().contentType().is(MediaType.parse("application/x-ndjson")));
    assertParquetMetadata(latest.text());
    assertTrue(latest.text().contains("eventTime") || latest.text().contains("date"));

    // Explicit parquet keeps protocol and schemaString, not delta wrappers.
    Response parquet = metadata(endpoint, bearer, "responseformat=parquet");
    assertVersion("2", parquet);
    assertParquetMetadata(parquet.text());
    // Dual responseformat prefers delta over parquet.
    Response both = metadata(endpoint, bearer, "responseformat=delta,parquet");
    assertVersion("2", both);
    assertDeltaMetadata(both.text());
    // Delta-only wraps Kernel protocol and metadata.
    Response delta = metadata(endpoint, bearer, "responseformat=delta");
    assertVersion("2", delta);
    assertDeltaMetadata(delta.text());

    // Historical version 0, and as of the first commit time, return that snapshot.
    assertVersion("0", metadata(endpoint + "?version=0", bearer, null));
    assertVersion("0", metadata(endpoint + "?timestamp=2021-05-04T17:24:06Z", bearer, null));
    // As of before version 0 is INVALID_PARAMETER_VALUE.
    assertInvalid(metadata(endpoint + "?timestamp=1970-01-01T00:00:00Z", bearer, null));
    // version and timestamp together are mutually exclusive.
    assertInvalid(metadata(endpoint + "?version=1&timestamp=2021-05-04T17:24:06Z", bearer, null));
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void queriesDeltaMetadataForDeletionVectorsTable() {
    String bearer =
        shareTable("dv-metadata", "main.sales.deletionvectors", "sales.deletionvectors");
    String endpoint =
        PROTOCOL + "/shares/dv-metadata/schemas/sales/tables/deletionvectors/metadata";

    // Deletion-vector table metadata in delta format includes the deletionVectors feature.
    Response delta = metadata(endpoint, bearer, "responseformat=delta");
    assertEquals(200, delta.status());
    assertTrue(delta.headers().contains("Delta-Table-Version"));
    assertDeltaMetadata(delta.text());
    assertTrue(delta.text().contains("deletionVectors"), delta.text());

    // Dual responseformat still prefers delta for a reader-v3 table.
    Response both = metadata(endpoint, bearer, "responseformat=delta,parquet");
    assertEquals(200, both.status());
    assertDeltaMetadata(both.text());
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void queriesMetadataThroughASchemaGrant() {
    createShare("schema-metadata");
    addObject("schema-metadata", "SCHEMA", "main.sales", "sales");
    String bearer = createAndActivateRecipient("schema-metadata-partner");
    grant("schema-metadata", "schema-metadata-partner");

    // A SCHEMA grant can Query Table Metadata for catalog children (table1).
    Response metadata =
        metadata(
            PROTOCOL + "/shares/schema-metadata/schemas/SALES/tables/TABLE1/metadata",
            bearer,
            null);
    assertVersion("2", metadata);
    assertParquetMetadata(metadata.text());
  }

  private static void assertParquetMetadata(String ndjson) {
    assertTrue(ndjson.contains("\"protocol\""), ndjson);
    assertTrue(ndjson.contains("\"metaData\""), ndjson);
    assertTrue(ndjson.contains("\"schemaString\""), ndjson);
    assertFalse(ndjson.contains("\"deltaProtocol\""), ndjson);
    assertFalse(ndjson.contains("\"deltaMetadata\""), ndjson);
  }

  private static void assertDeltaMetadata(String ndjson) {
    assertTrue(ndjson.contains("\"deltaProtocol\""), ndjson);
    assertTrue(ndjson.contains("\"deltaMetadata\""), ndjson);
  }

  private static String shareTable(String share, String catalogName, String sharedAs) {
    createShare(share);
    addObject(share, "TABLE", catalogName, sharedAs);
    String bearer = createAndActivateRecipient(share + "-partner");
    grant(share, share + "-partner");
    return bearer;
  }

  private static Response metadata(String path, String bearer, String capabilities) {
    return capabilities == null
        ? server.get(path, bearer)
        : server.call(
            HttpMethod.GET, path, bearer, null, "delta-sharing-capabilities", capabilities);
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
