package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertFalse;

import io.opensharing.TestServer.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

/**
 * Protocol tests that read Delta tables through Kernel. The server's local catalog points at Delta
 * logs written to a temporary directory: {@code main.sales.orders} with 3 commits, {@code
 * main.sales.customers} with 1, and {@code main.hr.salaries} with 4, each adding one data file, and
 * {@code main.ops.events} with 2 commits and no data files. Commit {@code n} of each table is
 * timestamped 2024-01-01T00:00:00Z plus {@code n} hours. Table {@code t} has metadata id {@code t}
 * and one {@code long} column, {@code id}. {@code main.lake.unwritten} is a Delta table with no
 * log, {@code main.lake.raw} has no format, {@code main.lake.future} requires a reader feature
 * Kernel does not know, {@code main.lake.gapped} has a log that starts at version 1, so its first
 * commit is missing, {@code main.lake.noncontiguous} has commits 0 and 2, so version 1 is
 * missing, {@code main.lake.invalidlog}, in a directory with a space in its name, enables in-commit
 * timestamps but its first commit has no CommitInfo, and {@code main.lake.futuresince} needs a
 * reader feature Kernel does not know at version 0 but not at its latest.
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
  private static final String FUTURE_PROTOCOL_ACTION =
      "{\"protocol\":{\"minReaderVersion\":3,\"minWriterVersion\":7,"
          + "\"readerFeatures\":[\"futureFeature\"],\"writerFeatures\":[\"futureFeature\"]}}\n";
  private static final String COMMIT_INFO_ACTION = "{\"commitInfo\":{}}\n";
  private static final String ICT_PROTOCOL_ACTION =
      "{\"protocol\":{\"minReaderVersion\":1,\"minWriterVersion\":7,"
          + "\"writerFeatures\":[\"inCommitTimestamp\"]}}\n";
  private static final String ICT_METADATA_ACTION =
      METADATA_ACTION.formatted("invalidlog").replace(
          "\"configuration\":{}",
          "\"configuration\":{\"delta.enableInCommitTimestamps\":\"true\","
              + "\"delta.inCommitTimestampEnablementVersion\":\"0\","
              + "\"delta.inCommitTimestampEnablementTimestamp\":\"1704067200000\"}");

  /** A directory name with a space, which a table path spells two ways: raw and URI-escaped. */
  protected static final String INVALID_LOG_DIRECTORY = "invalid log";

  @TempDir static Path tables;

  @BeforeAll
  static void startLocalDeltaServer() {
    String orders = deltaTable("orders", 3);
    String customers = deltaTable("customers", 1);
    String salaries = deltaTable("salaries", 4);
    String events = deltaTable("events", 2, false);
    write(
        "future/_delta_log/%020d.json".formatted(0),
        FUTURE_PROTOCOL_ACTION + METADATA_ACTION.formatted("future"));
    appendCommit("gapped", 1);
    // Commits 0 and 2 with version 1 missing, a gap Kernel rejects as non-contiguous.
    write(
        "noncontiguous/_delta_log/%020d.json".formatted(0),
        PROTOCOL_ACTION + METADATA_ACTION.formatted("noncontiguous") + COMMIT_INFO_ACTION);
    write("noncontiguous/_delta_log/%020d.json".formatted(2), COMMIT_INFO_ACTION);
    // In-commit timestamps enabled, with a first commit that carries no CommitInfo: Kernel calls
    // the log invalid without any file being missing.
    write(
        INVALID_LOG_DIRECTORY + "/_delta_log/%020d.json".formatted(0),
        ICT_PROTOCOL_ACTION + ICT_METADATA_ACTION);
    // A reader feature Kernel does not know at version 0, which the latest version does not need.
    write(
        "futuresince/_delta_log/%020d.json".formatted(0),
        FUTURE_PROTOCOL_ACTION + METADATA_ACTION.formatted("futuresince"));
    write(
        "futuresince/_delta_log/%020d.json".formatted(1),
        PROTOCOL_ACTION + METADATA_ACTION.formatted("futuresince") + COMMIT_INFO_ACTION);
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
          - identifier: main.ops
            type: SCHEMA
          - identifier: main.ops.events
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
          - identifier: main.lake.future
            attributes:
              subtype: MANAGED
            storageLocation: %s
            format: delta
          - identifier: main.lake.gapped
            attributes:
              subtype: MANAGED
            storageLocation: %s
            format: delta
          - identifier: main.lake.noncontiguous
            attributes:
              subtype: MANAGED
            storageLocation: %s
            format: delta
          - identifier: main.lake.invalidlog
            attributes:
              subtype: MANAGED
            storageLocation: %s
            format: delta
          - identifier: main.lake.futuresince
            attributes:
              subtype: MANAGED
            storageLocation: %s
            format: delta
        """
            .formatted(
                orders,
                customers,
                salaries,
                events,
                location("unwritten"),
                location("raw"),
                location("future"),
                location("gapped"),
                location("noncontiguous"),
                unescapedLocation(INVALID_LOG_DIRECTORY),
                location("futuresince"));
    Properties properties = new Properties();
    properties.setProperty("catalog.local.file", write("local-catalog.yml", catalog).toString());
    restartServer(properties);
  }

  /** Asserts that {@code response} does not name where the tables are stored, in any spelling. */
  protected static void assertNoStoragePath(Response response) {
    List<String> spellings =
        List.of(tables.toString(), tables.toUri().toString(), INVALID_LOG_DIRECTORY, "%20");
    for (String path : spellings) {
      assertFalse(response.text().contains(path), response.text());
    }
  }

  /** The storage location of table {@code name}. */
  protected static String location(String name) {
    return tables.resolve(name).toUri().toString();
  }

  /** The storage location of table {@code name} with its spaces as they are, not URI-escaped. */
  private static String unescapedLocation(String name) {
    return "file:" + tables.resolve(name);
  }

  private static String deltaTable(String name, int commits) {
    return deltaTable(name, commits, true);
  }

  /**
   * Writes a Delta log of {@code commits} commits, each adding a data file if {@code files}, and
   * returns the table's location.
   */
  private static String deltaTable(String name, int commits, boolean files) {
    for (int version = 0; version < commits; version++) {
      writeCommit(name, version, files);
    }
    return location(name);
  }

  /** Appends commit {@code version}, with no data file, to the log of table {@code name}. */
  protected static void appendCommit(String name, int version) {
    writeCommit(name, version, false);
  }

  private static void writeCommit(String name, int version, boolean files) {
    String actions = version == 0 ? PROTOCOL_ACTION + METADATA_ACTION.formatted(name) : "";
    String change = files ? ADD_ACTION.formatted(version) : COMMIT_INFO_ACTION;
    Path commit = write(name + "/_delta_log/%020d.json".formatted(version), actions + change);
    try {
      Files.setLastModifiedTime(commit, FileTime.from(COMMIT_TIME.plus(Duration.ofHours(version))));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
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
