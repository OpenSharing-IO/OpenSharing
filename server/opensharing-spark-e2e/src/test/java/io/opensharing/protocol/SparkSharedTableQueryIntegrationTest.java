package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import org.apache.spark.sql.DataFrameReader;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.execution.FileSourceScanExec;
import org.apache.spark.sql.execution.SparkPlan;
import org.apache.spark.sql.streaming.DataStreamReader;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.streaming.Trigger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import scala.jdk.javaapi.CollectionConverters;

/**
 * Recipient Query Table through the OSS Spark Delta Sharing connector. Each case loops parquet and
 * delta response formats over the same expected rows. Individual cases are skipped unless their
 * repository credentials are present.
 */
@Timeout(600)
@EnabledIf("anyCloud")
class SparkSharedTableQueryIntegrationTest extends ProtocolApiSupport {

  private static final List<String> FORMATS = List.of("parquet", "delta");

  /** AWS table1, matching delta-sharing-spark's table1 snapshot. Version 1 adds the first row. */
  private static final List<List<String>> TABLE1 =
      List.of(
          List.of("2021-04-27 23:32:02.07", "2021-04-28"),
          List.of("2021-04-27 23:32:22.421", "2021-04-28"));

  private static final String[] TABLE1_COLUMNS = {
    "CAST(eventTime AS STRING) AS eventTime", "CAST(date AS STRING) AS date"
  };

  /** CDF versions 0–3, matching delta-sharing-spark's cdf_table_cdf_enabled table_changes. */
  private static final List<List<String>> CDF_CHANGES =
      List.of(
          List.of("1", "1", "2020-01-01", "insert", "1", "1651272635000"),
          List.of("2", "2", "2020-01-01", "insert", "1", "1651272635000"),
          List.of("3", "3", "2020-01-01", "insert", "1", "1651272635000"),
          List.of("2", "2", "2020-01-01", "update_preimage", "3", "1651272660000"),
          List.of("2", "2", "2020-02-02", "update_postimage", "3", "1651272660000"),
          List.of("3", "3", "2020-01-01", "delete", "2", "1651272655000"));

  private static final String[] PEOPLE_COLUMNS = {
    "CAST(name AS STRING) AS name",
    "CAST(age AS STRING) AS age",
    "CAST(birthday AS STRING) AS birthday"
  };

  private static final String[] CHANGE_COLUMNS = {
    "CAST(name AS STRING) AS name",
    "CAST(age AS STRING) AS age",
    "CAST(birthday AS STRING) AS birthday",
    "_change_type",
    "CAST(_commit_version AS STRING) AS _commit_version",
    "_commit_timestamp"
  };

  /**
   * A stream of cdf_table_cdf_enabled from version 0 that ignores deletes and changes, matching
   * delta-sharing-spark's DeltaSharingSourceSuite.
   */
  private static final List<List<String>> CDF_STREAM =
      List.of(
          List.of("1", "1", "2020-01-01"),
          List.of("2", "2", "2020-01-01"),
          List.of("3", "3", "2020-01-01"),
          List.of("2", "2", "2020-02-02"));

  /** streaming_cdf_table's Change Data Feed, matching delta-sharing-spark's CDF stream suite. */
  private static final List<List<String>> STREAMING_CDF =
      List.of(
          List.of("1", "1", "2020-01-01", "insert", "1", "1667325629000"),
          List.of("2", "2", "2020-01-01", "insert", "1", "1667325629000"),
          List.of("1", "1", "2021-01-01", "insert", "2", "1667325636000"),
          List.of("2", "2", "2021-01-01", "insert", "2", "1667325636000"),
          List.of("3", "3", "2021-01-01", "insert", "2", "1667325636000"),
          List.of("1", "1", "2020-01-01", "update_preimage", "3", "1667325644000"),
          List.of("1", "1", "2020-02-02", "update_postimage", "3", "1667325644000"),
          List.of("1", "1", "2021-01-01", "update_preimage", "3", "1667325644000"),
          List.of("1", "1", "2020-02-02", "update_postimage", "3", "1667325644000"),
          List.of("2", "2", "2020-01-01", "update_preimage", "3", "1667325644000"),
          List.of("2", "2", "2020-02-02", "update_postimage", "3", "1667325644000"),
          List.of("2", "2", "2021-01-01", "update_preimage", "3", "1667325644000"),
          List.of("2", "2", "2020-02-02", "update_postimage", "3", "1667325644000"),
          List.of("1", "1", "2020-02-02", "delete", "4", "1667325812000"),
          List.of("1", "1", "2020-02-02", "delete", "4", "1667325812000"),
          List.of("2", "2", "2020-02-02", "delete", "4", "1667325812000"),
          List.of("2", "2", "2020-02-02", "delete", "4", "1667325812000"));

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
            // Keeps the file scan, and its numFiles metric, in the executed plan.
            .config("spark.sql.adaptive.enabled", "false")
            // TABLE1's eventTime strings and the timestamp options below are in this zone.
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
      Dataset<Row> rows = read(format).load(table);
      assertEquals(List.of("eventTime", "date"), List.of(rows.columns()));
      assertRows(rows, TABLE1, TABLE1_COLUMNS);
      assertRows(rows.filter("date = '2021-04-28'"), TABLE1, TABLE1_COLUMNS);
      assertRows(rows.filter("date = '1900-01-01'"), List.of(), TABLE1_COLUMNS);
      List<List<String>> limited = cells(rows.limit(1), TABLE1_COLUMNS);
      assertEquals(1, limited.size(), limited.toString());
      assertTrue(TABLE1.contains(limited.get(0)), limited.toString());

      assertRows(read(format).option("versionAsOf", 2).load(table), TABLE1, TABLE1_COLUMNS);
      // Versions 0 and 1 were committed in the same second and version 2 one second later.
      assertRows(
          read(format).option("timestampAsOf", "2021-05-04 10:24:06.5").load(table),
          TABLE1.subList(0, 1),
          TABLE1_COLUMNS);
      assertRows(
          read(format).option("timestampAsOf", "2021-05-04 10:24:07").load(table),
          TABLE1,
          TABLE1_COLUMNS);
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void prunesAwsTable1FilesOnTheServer() {
    String table = shared("table1-pruning-spark", "main.sales.table1", "sales.table1");
    // A parquet client keeps every file the server lists for an unpartitioned table.
    Dataset<Row> rows = read("parquet").load(table);
    assertEquals(2, scannedFiles(rows));
    assertEquals(0, scannedFiles(rows.filter("date = '1900-01-01'")));
    assertEquals(1, scannedFiles(rows.limit(1)));
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void queriesAwsChangeDataFeed() {
    String table = shared("cdf-spark", "main.sales.cdf", "sales.cdf");
    for (String format : FORMATS) {
      assertRows(
          changes(format)
              .option("startingVersion", 0)
              .option("endingVersion", 3)
              .load(table),
          CDF_CHANGES,
          CHANGE_COLUMNS);
      // Versions 1 and 2's commit times.
      assertRows(
          changes(format)
              .option("startingTimestamp", "2022-04-29 15:50:35")
              .option("endingTimestamp", "2022-04-29 15:50:55")
              .load(table),
          CDF_CHANGES.stream().filter(row -> !row.get(4).equals("3")).toList(),
          CHANGE_COLUMNS);

      String afterLatest =
          errorMessages(
              () ->
                  changes(format)
                      .option("startingTimestamp", "2030-01-01 00:00:00")
                      .load(table)
                      .collectAsList());
      assertTrue(
          afterLatest.contains("is after the latest available version"),
          afterLatest);
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void streamsAwsTableFromStartingVersion() throws Exception {
    String table = shared("stream-spark", "main.sales.cdf", "sales.cdf");
    for (String format : FORMATS) {
      // Versions 2 and 3 delete and update rows, which a stream otherwise fails on.
      DataStreamReader stream =
          stream(format)
              .option("startingVersion", 0)
              .option("ignoreDeletes", "true")
              .option("ignoreChanges", "true");
      assertRows(streamed(stream.load(table), "cdf_" + format, PEOPLE_COLUMNS), CDF_STREAM, "*");
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void streamsAwsChangeDataFeed() throws Exception {
    String table = shared("cdf-stream-spark", "main.sales.streamingcdf", "sales.streamingcdf");
    // delta-sharing-spark streams a Change Data Feed only in parquet.
    Dataset<Row> fromVersion =
        stream("parquet").option("readChangeFeed", "true").option("startingVersion", 0).load(table);
    assertRows(streamed(fromVersion, "streamingcdf_version", CHANGE_COLUMNS), STREAMING_CDF, "*");
    // Before version 0, so the stream starts there.
    Dataset<Row> fromTimestamp =
        stream("parquet")
            .option("readChangeFeed", "true")
            .option("startingTimestamp", "2022-01-01 00:00:00")
            .load(table);
    assertRows(
        streamed(fromTimestamp, "streamingcdf_timestamp", CHANGE_COLUMNS), STREAMING_CDF, "*");
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
                    () -> read(format).load(table).collectAsList())
                .getMessage();
        assertTrue(error.contains("respondedFormat(delta) != requestedFormat(parquet)"), error);
        continue;
      }
      Dataset<Row> rows = read(format).load(table);
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
      Dataset<Row> rows = read(format).load(table);
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

  private static DataFrameReader read(String format) {
    return spark.read().format("deltaSharing").option("responseFormat", format);
  }

  private static DataFrameReader changes(String format) {
    return read(format).option("readChangeFeed", "true");
  }

  private static DataStreamReader stream(String format) {
    return spark.readStream().format("deltaSharing").option("responseFormat", format);
  }

  /** Runs {@code stream} until it has read every available version, then returns its rows. */
  private Dataset<Row> streamed(Dataset<Row> stream, String name, String... columns)
      throws Exception {
    StreamingQuery query =
        stream
            .selectExpr(columns)
            .writeStream()
            .format("memory")
            .queryName(name)
            .option("checkpointLocation", tmp.resolve(name).toString())
            .trigger(Trigger.AvailableNow())
            .start();
    try {
      query.awaitTermination();
    } finally {
      query.stop();
    }
    return spark.table(name);
  }

  /** The number of files Spark's scan read for {@code rows}, after the server pruned them. */
  private static long scannedFiles(Dataset<Row> rows) {
    rows.collectAsList();
    SparkPlan plan = rows.queryExecution().executedPlan();
    long files = 0;
    for (SparkPlan leaf : CollectionConverters.asJava(plan.collectLeaves())) {
      if (leaf instanceof FileSourceScanExec scan) {
        files += scan.metrics().apply("numFiles").value();
      }
    }
    return files;
  }

  /** The messages of what {@code read} throws and of each of its causes. */
  private static String errorMessages(Executable read) {
    Throwable error = assertThrows(Exception.class, read);
    StringBuilder messages = new StringBuilder();
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      messages.append(cause.getMessage()).append('\n');
    }
    return messages.toString();
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
        Object value = row.get(i);
        // _commit_timestamp is a timestamp in delta responses and epoch milliseconds in parquet.
        cells.add(
            value == null
                ? null
                : value instanceof Timestamp timestamp
                    ? String.valueOf(timestamp.getTime())
                    : String.valueOf(value));
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
