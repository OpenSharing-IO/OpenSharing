package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Recipient Query Table through the OSS Spark Delta Sharing connector. Each case loops parquet and
 * delta response formats over the same expected rows. Individual cases are skipped unless their
 * repository credentials are present.
 */
@Timeout(600)
@EnabledIf("anyCloud")
class SparkSharedTableQueryIntegrationTest extends ProtocolApiSupport {

  private static final List<String> FORMATS = List.of("parquet", "delta");

  /** AWS table1, matching delta-sharing-spark's table1 snapshot. */
  private static final List<List<String>> TABLE1 =
      List.of(
          List.of("2021-04-27 23:32:02.07", "2021-04-28"),
          List.of("2021-04-27 23:32:22.421", "2021-04-28"));

  /** CDF versions 0–3, matching delta-sharing-spark's cdf_table_cdf_enabled table_changes. */
  private static final List<List<String>> CDF_CHANGES =
      List.of(
          List.of("1", "1", "2020-01-01", "insert", "1"),
          List.of("2", "2", "2020-01-01", "insert", "1"),
          List.of("3", "3", "2020-01-01", "insert", "1"),
          List.of("2", "2", "2020-01-01", "update_preimage", "3"),
          List.of("2", "2", "2020-02-02", "update_postimage", "3"),
          List.of("3", "3", "2020-01-01", "delete", "2"));

  /** Live ids after deletion vectors, matching delta-sharing Python's DV snapshot. */
  private static final List<List<String>> DELETION_VECTORS =
      List.of(
          List.of("3", "3"),
          List.of("4", "4"),
          List.of("5", "5"),
          List.of("6", "6"),
          List.of("7", "7"),
          List.of("8", "8"),
          List.of("9", "9"));

  private static final List<List<String>> CLOUD_TABLE1 = List.of(List.of("foo bar", "foo bar"));

  private static SparkSession spark;

  @TempDir Path tmp;

  static boolean anyCloud() {
    return aws() || azure() || gcs();
  }

  @BeforeAll
  static void startCloudServerAndSpark() {
    Properties properties = new Properties();
    properties.setProperty("catalog.local.file", "classpath:local-catalog-cloud.yml");
    restartServer(properties);
    spark =
        SparkSession.builder()
            .master("local[2]")
            .appName("opensharing-spark-e2e")
            .config("spark.ui.enabled", "false")
            .config("spark.driver.host", "127.0.0.1")
            .config("spark.sql.shuffle.partitions", "2")
            // TABLE1's eventTime strings are in this zone.
            .config("spark.sql.session.timeZone", "America/Los_Angeles")
            .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
            .config(
                "spark.sql.catalog.spark_catalog",
                "org.apache.spark.sql.delta.catalog.DeltaCatalog")
            .getOrCreate();
  }

  @AfterAll
  static void stopSpark() {
    if (spark != null) {
      spark.stop();
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void queriesAwsTable1() {
    String table = shared("table1-spark", "main.sales.table1", "sales.table1");
    for (String format : FORMATS) {
      Dataset<Row> rows = snapshot(table, format);
      assertEquals(List.of("eventTime", "date"), List.of(rows.columns()));
      assertRows(
          rows, TABLE1, "CAST(eventTime AS STRING) AS eventTime", "CAST(date AS STRING) AS date");
      assertRows(
          rows.filter("date = '2021-04-28'"),
          TABLE1,
          "CAST(eventTime AS STRING) AS eventTime",
          "CAST(date AS STRING) AS date");
      assertRows(
          rows.filter("date = '1900-01-01'"),
          List.of(),
          "CAST(eventTime AS STRING) AS eventTime",
          "CAST(date AS STRING) AS date");
      List<List<String>> limited =
          cells(
              rows.limit(1),
              "CAST(eventTime AS STRING) AS eventTime",
              "CAST(date AS STRING) AS date");
      assertEquals(1, limited.size(), limited.toString());
      assertTrue(TABLE1.contains(limited.get(0)), limited.toString());

      Dataset<Row> atVersion =
          spark
              .read()
              .format("deltaSharing")
              .option("responseFormat", format)
              .option("versionAsOf", 2)
              .load(table);
      assertRows(
          atVersion,
          TABLE1,
          "CAST(eventTime AS STRING) AS eventTime",
          "CAST(date AS STRING) AS date");
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void queriesAwsChangeDataFeed() {
    String table = shared("cdf-spark", "main.sales.cdf", "sales.cdf");
    for (String format : FORMATS) {
      Dataset<Row> changes =
          spark
              .read()
              .format("deltaSharing")
              .option("responseFormat", format)
              .option("readChangeFeed", "true")
              .option("startingVersion", 0)
              .option("endingVersion", 3)
              .load(table);
      assertRows(
          changes,
          CDF_CHANGES,
          "CAST(name AS STRING) AS name",
          "CAST(age AS STRING) AS age",
          "CAST(birthday AS STRING) AS birthday",
          "_change_type",
          "CAST(_commit_version AS STRING) AS _commit_version");
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void queriesAwsDeletionVectors() {
    String table = shared("dv-spark", "main.sales.deletionvectors", "sales.deletionvectors");
    for (String format : FORMATS) {
      if (format.equals("parquet")) {
        // Reader-v3 deletion vectors cannot be served as parquet, so the server answers in delta.
        String error =
            assertThrows(
                    IllegalArgumentException.class,
                    () -> snapshot(table, format).collectAsList())
                .getMessage();
        assertTrue(error.contains("respondedFormat(delta) != requestedFormat(parquet)"), error);
        continue;
      }
      Dataset<Row> rows = snapshot(table, format);
      assertRows(
          rows, DELETION_VECTORS, "CAST(id AS STRING) AS id", "CAST(value AS STRING) AS value");
      assertRows(
          rows.filter("id = 3"),
          List.of(List.of("3", "3")),
          "CAST(id AS STRING) AS id",
          "CAST(value AS STRING) AS value");
      assertRows(
          rows.filter("id = 1"),
          List.of(),
          "CAST(id AS STRING) AS id",
          "CAST(value AS STRING) AS value");
      List<List<String>> limited =
          cells(rows.limit(1), "CAST(id AS STRING) AS id", "CAST(value AS STRING) AS value");
      assertEquals(1, limited.size(), limited.toString());
      assertTrue(DELETION_VECTORS.contains(limited.get(0)), limited.toString());
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AZURE_TEST_ACCOUNT_KEY", matches = ".+")
  void queriesAzureTable1() {
    queriesCloudCopy("azure-spark", "main.sales.azure", "sales.azure");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "GOOGLE_APPLICATION_CREDENTIALS", matches = ".+")
  void queriesGcsTable1() {
    queriesCloudCopy("gcs-spark", "main.sales.gcs", "sales.gcs");
  }

  private void queriesCloudCopy(String share, String catalogName, String sharedAs) {
    String table = shared(share, catalogName, sharedAs);
    for (String format : FORMATS) {
      Dataset<Row> rows = snapshot(table, format);
      assertRows(rows, CLOUD_TABLE1, "CAST(c1 AS STRING) AS c1", "CAST(c2 AS STRING) AS c2");
      assertRows(
          rows.filter("c2 = 'foo bar'"),
          CLOUD_TABLE1,
          "CAST(c1 AS STRING) AS c1",
          "CAST(c2 AS STRING) AS c2");
      assertRows(
          rows.filter("c2 = 'baz'"),
          List.of(),
          "CAST(c1 AS STRING) AS c1",
          "CAST(c2 AS STRING) AS c2");
    }
  }

  private static Dataset<Row> snapshot(String table, String responseFormat) {
    return spark.read().format("deltaSharing").option("responseFormat", responseFormat).load(table);
  }

  private static void assertRows(
      Dataset<Row> rows, List<List<String>> expected, String... columns) {
    List<List<String>> want = new ArrayList<>(expected);
    want.sort(Comparator.comparing(Object::toString));
    assertEquals(want, cells(rows, columns));
  }

  private static List<List<String>> cells(Dataset<Row> rows, String... columns) {
    List<List<String>> actual = new ArrayList<>();
    for (Row row : rows.selectExpr(columns).collectAsList()) {
      List<String> cells = new ArrayList<>();
      for (int i = 0; i < row.size(); i++) {
        cells.add(row.isNullAt(i) ? null : String.valueOf(row.get(i)));
      }
      actual.add(cells);
    }
    actual.sort(Comparator.comparing(Object::toString));
    return actual;
  }

  private String shared(String share, String catalogName, String sharedAs) {
    String bearer = shareTable(share, catalogName, sharedAs);
    return profile(share, bearer) + "#" + share + "." + sharedAs;
  }

  private String profile(String share, String bearer) {
    Path file = tmp.resolve(share + ".share");
    try {
      Files.writeString(
          file,
          """
          {
            "shareCredentialsVersion": 1,
            "endpoint": "http://127.0.0.1:%d%s",
            "bearerToken": "%s"
          }
          """
              .formatted(server.server().port(), PROTOCOL, bearer));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return file.toAbsolutePath().toString();
  }

  private static String shareTable(String share, String catalogName, String sharedAs) {
    createShare(share);
    addObject(share, "TABLE", catalogName, sharedAs);
    String bearer = createAndActivateRecipient(share + "-partner");
    grant(share, share + "-partner");
    return bearer;
  }

  private static boolean aws() {
    return present("AWS_ACCESS_KEY_ID") && present("AWS_SECRET_ACCESS_KEY");
  }

  private static boolean azure() {
    return present("AZURE_TEST_ACCOUNT_KEY");
  }

  private static boolean gcs() {
    return present("GOOGLE_APPLICATION_CREDENTIALS");
  }

  private static boolean present(String name) {
    String value = System.getenv(name);
    return value != null && !value.isBlank();
  }
}
