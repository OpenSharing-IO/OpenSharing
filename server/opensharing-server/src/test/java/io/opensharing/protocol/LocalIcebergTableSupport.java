package io.opensharing.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

/**
 * Iceberg endpoint tests with temporary catalog tables. The catalog contains Delta tables in {@code
 * main.sales} and {@code main.hr}, and Iceberg tables in {@code main.lake}: {@code trips} has a
 * metadata file, {@code stations} and {@code unpointed} have no metadata location, and {@code
 * astray} points outside its location.
 */
abstract class LocalIcebergTableSupport extends ProtocolApiSupport {

  static final String TABLE_UUID = "9c12d441-03fe-4693-9a96-a0705ddf69c1";
  static final String TRIPS_METADATA = "trips/metadata/00001-" + TABLE_UUID + ".metadata.json";

  @TempDir static Path tables;

  @BeforeAll
  static void startLocalIcebergServer() {
    write(TRIPS_METADATA, metadata(location("trips")));
    write("elsewhere/metadata/00001.metadata.json", metadata(location("elsewhere")));
    Properties properties = new Properties();
    properties.setProperty("catalog.local.file", write("local-catalog.yml", catalog()).toString());
    restartServer(properties);
  }

  /** The storage location of table {@code name}, with a trailing slash. */
  static String location(String name) {
    return file(name + "/");
  }

  /** The URI of the file at {@code relativePath} under the tables directory. */
  static String file(String relativePath) {
    return tables.toUri() + relativePath;
  }

  /** The metadata JSON of an empty format-version 2 table with one long column, {@code id}. */
  static String metadata(String location) {
    return """
    {"format-version": 2, "table-uuid": "%s", "location": "%s",
     "last-sequence-number": 0, "last-updated-ms": 1704067200000, "last-column-id": 1,
     "current-schema-id": 0,
     "schemas": [{"type": "struct", "schema-id": 0,
                  "fields": [{"id": 1, "name": "id", "required": false, "type": "long"}]}],
     "default-spec-id": 0, "partition-specs": [{"spec-id": 0, "fields": []}],
     "last-partition-id": 999, "default-sort-order-id": 0,
     "sort-orders": [{"order-id": 0, "fields": []}], "properties": {},
     "current-snapshot-id": -1, "refs": {}, "snapshots": [], "snapshot-log": [],
     "metadata-log": []}
    """
        .formatted(TABLE_UUID, location);
  }

  private static String catalog() {
    try (InputStream in =
        LocalIcebergTableSupport.class.getResourceAsStream("/local-catalog-iceberg.yml")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8)
          .replace("${tables}", tables.toUri().toString())
          .replace("${aliceToken}", ALICE);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Path write(String relativePath, String content) {
    Path path = tables.resolve(relativePath);
    try {
      Files.createDirectories(path.getParent());
      return Files.writeString(path, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
