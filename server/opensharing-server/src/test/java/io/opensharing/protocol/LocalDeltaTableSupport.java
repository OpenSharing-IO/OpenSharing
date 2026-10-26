package io.opensharing.protocol;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

/**
 * Protocol tests that read Delta tables through Kernel. The server's local catalog points at Delta
 * logs written to a temporary directory: {@code main.sales.orders} with 3 commits, {@code
 * main.sales.customers} with 1, and {@code main.hr.salaries} with 4. Commit {@code n} of each table
 * is timestamped 2024-01-01T00:00:00Z plus {@code n} hours, and table {@code t} has metadata id
 * {@code t} and one {@code long} column, {@code id}. {@code main.lake.unwritten} is a Delta table
 * with no log, and {@code main.lake.raw} has no format.
 */
abstract class LocalDeltaTableSupport extends ProtocolApiSupport {

  private static final Instant COMMIT_TIME = Instant.parse("2024-01-01T00:00:00Z");

  private static final String PROTOCOL_ACTION =
      "{\"protocol\":{\"minReaderVersion\":1,\"minWriterVersion\":2}}\n";
  private static final String METADATA_ACTION =
      "{\"metaData\":{\"id\":\"%s\",\"format\":{\"provider\":\"parquet\",\"options\":{}},"
          + "\"schemaString\":\"{\\\"type\\\":\\\"struct\\\",\\\"fields\\\":[{\\\"name\\\":"
          + "\\\"id\\\",\\\"type\\\":\\\"long\\\",\\\"nullable\\\":true,\\\"metadata\\\":{}}]}\","
          + "\"partitionColumns\":[],\"configuration\":{},\"createdTime\":0}}\n";
  private static final String ADD_ACTION =
      "{\"add\":{\"path\":\"part-%d.parquet\",\"partitionValues\":{},\"size\":1,"
          + "\"modificationTime\":0,\"dataChange\":true}}\n";

  @TempDir static Path tables;

  @BeforeAll
  static void startLocalDeltaServer() {
    String orders = deltaTable("orders", 3);
    String customers = deltaTable("customers", 1);
    String salaries = deltaTable("salaries", 4);
    String catalog =
        """
        principals:
          - bearerToken: alice-token
            userId: catalog-alice-id
            userName: alice
            privileges: [CREATE_SHARE, CREATE_RECIPIENT, USE_SHARE, USE_RECIPIENT]
        assets:
          - identifier: main.sales
            type: SCHEMA
          - identifier: main.sales.orders
            attributes:
              subtype: MANAGED
            storageLocation: %s
            format: delta
          - identifier: main.sales.customers
            attributes:
              subtype: MANAGED
            storageLocation: %s
            format: delta
          - identifier: main.hr
            type: SCHEMA
          - identifier: main.hr.salaries
            attributes:
              subtype: MANAGED
            storageLocation: %s
            format: delta
          - identifier: main.lake
            type: SCHEMA
          - identifier: main.lake.unwritten
            attributes:
              subtype: MANAGED
            storageLocation: %s
            format: delta
          - identifier: main.lake.raw
            attributes:
              subtype: EXTERNAL
            storageLocation: %s
        """
            .formatted(orders, customers, salaries, location("unwritten"), location("raw"));
    Properties properties = new Properties();
    properties.setProperty("catalog.local.file", write("local-catalog.yml", catalog).toString());
    restartServer(properties);
  }

  /** Writes a Delta log of {@code commits} commits and returns the table's location. */
  private static String deltaTable(String name, int commits) {
    for (int version = 0; version < commits; version++) {
      String actions = version == 0 ? PROTOCOL_ACTION + METADATA_ACTION.formatted(name) : "";
      Path commit =
          write(
              name + "/_delta_log/%020d.json".formatted(version),
              actions + ADD_ACTION.formatted(version));
      try {
        Files.setLastModifiedTime(
            commit, FileTime.from(COMMIT_TIME.plus(Duration.ofHours(version))));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return location(name);
  }

  private static String location(String name) {
    return tables.resolve(name).toUri().toString();
  }

  private static Path write(String relativePath, String content) {
    Path file = tables.resolve(relativePath);
    try {
      Files.createDirectories(file.getParent());
      return Files.writeString(file, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
