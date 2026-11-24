package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A recipient reads a shared Iceberg table through Spark's Iceberg REST catalog, configured the
 * way a profile file says: its {@code icebergEndpoint}, its bearer token, and the share as the
 * warehouse. The provider writes the table with Spark into a local Hadoop catalog, and the server's
 * local catalog points at its current metadata, so the case needs no cloud credentials.
 */
@Timeout(600)
class SparkIcebergRestCatalogIntegrationTest extends ProtocolApiSupport {

  @TempDir static Path tmp;

  private static SparkSession spark;

  @BeforeAll
  static void writeTheTableAndStartTheServer() {
    // Start Spark with a local Hadoop Iceberg catalog for the provider.
    spark =
        SparkSession.builder()
            .master("local[2]")
            .appName("opensharing-spark-iceberg-e2e")
            .config("spark.ui.enabled", "false")
            .config("spark.driver.host", "127.0.0.1")
            .config("spark.sql.shuffle.partitions", "2")
            .config("spark.sql.catalog.provider", "org.apache.iceberg.spark.SparkCatalog")
            .config("spark.sql.catalog.provider.type", "hadoop")
            .config("spark.sql.catalog.provider.warehouse", tmp.resolve("warehouse").toString())
            .getOrCreate();
    // Write an Iceberg table with two commits.
    spark.sql("CREATE TABLE provider.lake.trips (id BIGINT, city STRING) USING iceberg");
    spark.sql("INSERT INTO provider.lake.trips VALUES (1, 'Lisbon'), (2, 'Oslo')");
    spark.sql("INSERT INTO provider.lake.trips VALUES (3, 'Quito')");
    // Read where the table and its current metadata are.
    String location = tableLocation();
    String metadataLocation =
        spark
            .sql(
                "SELECT file FROM provider.lake.trips.metadata_log_entries"
                    + " ORDER BY timestamp DESC LIMIT 1")
            .first()
            .getString(0);

    // Point the server's local catalog at the table, and restart the server with it.
    String catalog =
        """
        principals:
          - bearerToken: alice-token
            userId: catalog-alice-id
            userName: alice
            privileges: [CREATE_SHARE, CREATE_RECIPIENT, USE_SHARE, USE_RECIPIENT]
        assets:
          - identifier: main.lake
            type: SCHEMA
          - identifier: main.lake.trips
            storageLocation: %s
            format: iceberg
            metadataLocation: %s
        """
            .formatted(location, metadataLocation);
    Properties properties = new Properties();
    properties.setProperty("catalog.local.file", write("local-catalog.yml", catalog).toString());
    restartServer(properties);
  }

  @AfterAll
  static void stopSpark() {
    if (spark != null) {
      spark.stop();
    }
  }

  @Test
  void readsASharedIcebergTableThroughTheRestCatalog() {
    // Share the table as rides.trips with a recipient.
    createShare("iceberg-spark");
    addObject("iceberg-spark", "TABLE", "main.lake.trips", "rides.trips");
    String bearer = createAndActivateRecipient("iceberg-spark-partner");
    grant("iceberg-spark", "iceberg-spark-partner");

    // Configure Spark's Iceberg REST catalog the way the profile file says.
    String icebergEndpoint = "http://127.0.0.1:" + server.server().port() + PROTOCOL + "/iceberg";
    String shared = "spark.sql.catalog.shared";
    spark.conf().set(shared, "org.apache.iceberg.spark.SparkCatalog");
    spark.conf().set(shared + ".type", "rest");
    spark.conf().set(shared + ".uri", icebergEndpoint);
    spark.conf().set(shared + ".warehouse", "iceberg-spark");
    spark.conf().set(shared + ".token", bearer);
    spark.conf().set(shared + ".header.X-Iceberg-Access-Delegation", "vended-credentials");

    // Spark lists the share's namespace and its table.
    assertEquals(List.of("rides"), strings("SHOW NAMESPACES IN shared", 0));
    assertEquals(List.of("trips"), strings("SHOW TABLES IN shared.rides", 1));
    // Spark reads all three rows through the catalog.
    List<Row> rows =
        spark.sql("SELECT id, city FROM shared.rides.trips ORDER BY id").collectAsList();
    assertEquals(3, rows.size());
    assertEquals(1L, rows.get(0).getLong(0));
    assertEquals("Lisbon", rows.get(0).getString(1));
    assertEquals("Quito", rows.get(2).getString(1));
  }

  /** The values of column {@code column} of {@code sql}'s result. */
  private static List<String> strings(String sql, int column) {
    return spark.sql(sql).collectAsList().stream().map(row -> row.getString(column)).toList();
  }

  /** Where Iceberg put the table, as its metadata records it. */
  private static String tableLocation() {
    return spark
        .sql("DESCRIBE TABLE EXTENDED provider.lake.trips")
        .filter("col_name = 'Location'")
        .first()
        .getString(1);
  }

  private static Path write(String relativePath, String content) {
    Path path = tmp.resolve(relativePath);
    try {
      return Files.writeString(path, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
