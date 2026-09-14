package io.opensharing.asset.delta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.asset.storage.HadoopStorage;
import io.opensharing.config.OpenSharingProperties;
import io.opensharing.http.ApiException;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Reads the three-commit Delta table under {@code src/test/resources/delta-table}: an append, a
 * second append, then an update that removes a file, adds its replacement and records the change. A
 * hand-written log is enough because nothing here opens a data file: the server lists them and signs
 * urls, and the bytes are the recipient's business.
 */
class DeltaLogReaderTest {

  private static final DeltaLogReader READER =
      new DeltaLogReader(new HadoopStorage(new OpenSharingProperties()));

  private static String tableRoot() throws IOException {
    return tableRoot("orders");
  }

  private static String tableRoot(String name) throws IOException {
    return new ClassPathResource("delta-table/" + name).getFile().getAbsolutePath();
  }

  private static long commitTime(String table, String commit) throws IOException {
    return new ClassPathResource("delta-table/" + table + "/_delta_log/" + commit + ".json")
        .getFile()
        .lastModified();
  }

  @Test
  void readsTheLatestVersionAndItsMetadata() throws Exception {
    DeltaSnapshot snapshot = READER.read(tableRoot(), null, DeltaVersion.latest(), false);

    assertEquals(2, snapshot.version());
    assertEquals(1, snapshot.protocol().minReaderVersion());
    assertEquals("11111111-2222-3333-4444-555555555555", snapshot.metadata().id());
    assertEquals("parquet", snapshot.metadata().formatProvider());
    assertEquals(List.of("country"), snapshot.metadata().partitionColumns());
    assertEquals(
        "true", snapshot.metadata().configuration().get("delta.enableChangeDataFeed"));
    assertTrue(snapshot.metadata().schemaString().contains("order_id"));
    assertTrue(snapshot.files().isEmpty(), "files are only listed when asked for");
    assertEquals(
        commitTime("orders", "00000000000000000002"),
        snapshot.timestamp(),
        "the version's commit time, which is what a client is told beside a version");
  }

  @Test
  void keepsThePartitionColumnsAsTheLogNamesAndOrdersThem() throws Exception {
    DeltaSnapshot snapshot =
        READER.read(tableRoot("inventory"), null, DeltaVersion.latest(), false);

    assertEquals(
        List.of("Country", "Region"),
        snapshot.metadata().partitionColumns(),
        "the log's own case and order, not the schema's order or a lower-cased copy");
  }

  @Test
  void refusesToListAFileTheTableDoesNotOwn() throws Exception {
    String root = tableRoot("cloned");
    ApiException e =
        assertThrows(
            ApiException.class, () -> READER.read(root, null, DeltaVersion.latest(), true));

    assertEquals(501, e.getStatus().value());
    assertTrue(e.getMessage().contains("outside the table's own directory"), e.getMessage());
  }

  @Test
  void refusesAPathThatClimbsOutOfTheTable() {
    ApiException e =
        assertThrows(
            ApiException.class, () -> DeltaLogReader.requireInsideTable("../orders/part-0.parquet"));

    assertEquals(501, e.getStatus().value());
  }

  @Test
  void decodesAPathTheLogWroteEscaped() {
    assertEquals(
        "country=New Zealand/part-0.parquet",
        DeltaLogReader.requireInsideTable("country=New%20Zealand/part-0.parquet"));
  }

  @Test
  void listsOnlyTheFilesStillActiveAtThatVersion() throws Exception {
    DeltaSnapshot snapshot = READER.read(tableRoot(), null, DeltaVersion.latest(), true);

    assertEquals(2, snapshot.files().size(), "the file removed in commit 2 is gone");
    DeltaSnapshot.File file =
        snapshot.files().stream()
            .filter(f -> f.path().contains("country=DE"))
            .findFirst()
            .orElseThrow();
    assertEquals(2345, file.size());
    assertEquals("DE", file.partitionValues().get("country"));
    assertTrue(file.stats().contains("numRecords"));
    assertTrue(file.path().startsWith("file:") || file.path().startsWith("/"));
  }

  @Test
  void readsTheChangeFeedAsAddsRemovesAndRecordedChanges() throws Exception {
    List<DeltaChanges.Entry> entries = READER.changes(tableRoot(), null, 2, 2, true, false);

    assertEquals(3, entries.size());
    assertEquals(
        List.of(DeltaChanges.Kind.ADD, DeltaChanges.Kind.CDF, DeltaChanges.Kind.REMOVE),
        files(entries).stream().map(DeltaChanges.FileChange::kind).sorted().toList(),
        "commit 2 removed a file, added its replacement and recorded the change");
    DeltaChanges.FileChange cdf =
        files(entries).stream()
            .filter(change -> change.kind() == DeltaChanges.Kind.CDF)
            .findFirst()
            .orElseThrow();
    assertTrue(cdf.path().contains("_change_data/"), cdf.path());
    assertEquals(2, cdf.version());
    assertEquals("NL", cdf.partitionValues().get("country"));
  }

  @Test
  void leavesOutTheRecordedChangesWhenOnlyTheCommittedFilesAreWanted() throws Exception {
    List<DeltaChanges.Entry> entries = READER.changes(tableRoot(), null, 2, 2, false, false);

    assertEquals(
        List.of(DeltaChanges.Kind.ADD, DeltaChanges.Kind.REMOVE),
        files(entries).stream().map(DeltaChanges.FileChange::kind).sorted().toList(),
        "a stream rebuilding the table from commits would double-count the change file");
  }

  @Test
  void spansEveryVersionInTheChangeWindow() throws Exception {
    List<DeltaChanges.Entry> entries = READER.changes(tableRoot(), null, 0, 2, true, false);

    assertEquals(5, entries.size(), "two appends, then a remove, an add and a change");
    assertEquals(0, entries.get(0).version());
  }

  @Test
  void reportsTheSchemaAndProtocolChangesInsideTheWindow() throws Exception {
    List<DeltaChanges.Entry> entries =
        READER.changes(tableRoot("evolving"), null, 0, 2, false, true);

    DeltaChanges.MetadataChange schema =
        entries.stream()
            .filter(DeltaChanges.MetadataChange.class::isInstance)
            .map(DeltaChanges.MetadataChange.class::cast)
            .reduce((first, second) -> second)
            .orElseThrow();
    assertEquals(1, schema.version(), "the commit that widened the schema");
    assertTrue(schema.metadata().schemaString().contains("note"), schema.metadata().schemaString());

    List<DeltaChanges.ProtocolChange> protocols =
        entries.stream()
            .filter(DeltaChanges.ProtocolChange.class::isInstance)
            .map(DeltaChanges.ProtocolChange.class::cast)
            .toList();
    assertEquals(
        List.of(0L, 2L),
        protocols.stream().map(DeltaChanges.ProtocolChange::version).toList(),
        "the protocol the table started with, then the commit that raised it");
    DeltaChanges.ProtocolChange raised = protocols.get(1);
    assertEquals(3, raised.protocol().minReaderVersion());
    assertEquals(List.of("deletionVectors"), raised.protocol().readerFeatures());
  }

  @Test
  void readsADeletionVectorAndWhereItsFileLives() throws Exception {
    DeltaSnapshot snapshot =
        READER.read(tableRoot("vectors"), null, DeltaVersion.latest(), true);

    DeltaSnapshot.DeletionVector vector = snapshot.files().get(0).deletionVector();
    assertEquals("u", vector.storageType(), "the log's own way of naming the file");
    assertEquals(2, vector.cardinality());
    assertTrue(
        vector.absolutePath().endsWith("deletion_vector_d3c4b5a6-1111-4222-8333-444455556666.bin"),
        vector.absolutePath());
    assertTrue(
        vector.absolutePath().startsWith(tableRoot("vectors")),
        "a vector is signed like a data file, so it has to be one of the table's own");
  }

  @Test
  void refusesABackwardsChangeWindow() throws Exception {
    String root = tableRoot();
    ApiException e =
        assertThrows(ApiException.class, () -> READER.changes(root, null, 2, 1, true, false));

    assertEquals(400, e.getStatus().value());
  }

  private static List<DeltaChanges.FileChange> files(List<DeltaChanges.Entry> entries) {
    return entries.stream()
        .filter(DeltaChanges.FileChange.class::isInstance)
        .map(DeltaChanges.FileChange.class::cast)
        .toList();
  }

  @Test
  void readsAnEarlierVersion() throws Exception {
    DeltaSnapshot snapshot = READER.read(tableRoot(), null, DeltaVersion.of(0), true);

    assertEquals(0, snapshot.version());
    assertEquals(1, snapshot.files().size());
  }

  @Test
  void reportsAVersionTheTableNeverHadAsABadRequest() throws Exception {
    String root = tableRoot();
    ApiException e =
        assertThrows(
            ApiException.class, () -> READER.read(root, null, DeltaVersion.of(99), false));

    assertEquals(400, e.getStatus().value());
  }

  @Test
  void reportsAMissingLogAsNotFound() {
    ApiException e =
        assertThrows(
            ApiException.class,
            () -> READER.read("/tmp/not-a-delta-table", null, DeltaVersion.latest(), false));

    assertEquals(404, e.getStatus().value());
  }

  @Test
  void reportsAStorageItCannotAddressAsUnimplemented() {
    ApiException e =
        assertThrows(
            ApiException.class,
            () -> READER.read("oss://bucket/table", null, DeltaVersion.latest(), false));

    assertEquals(501, e.getStatus().value());
    assertTrue(e.getMessage().contains("dir access mode"), e.getMessage());
  }
}
