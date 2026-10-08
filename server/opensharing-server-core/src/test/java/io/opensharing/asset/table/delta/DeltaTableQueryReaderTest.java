package io.opensharing.asset.table.delta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.delta.kernel.Table;
import io.delta.kernel.defaults.engine.DefaultEngine;
import io.delta.kernel.engine.Engine;
import io.opensharing.asset.table.signer.SignedUrl;
import io.opensharing.asset.table.signer.UrlSigners;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.catalog.TableFormat;
import io.opensharing.config.OpenSharingProperties;
import io.opensharing.http.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeltaTableQueryReaderTest {

  private static final String SCHEMA =
      "{\\\"type\\\":\\\"struct\\\",\\\"fields\\\":["
          + "{\\\"name\\\":\\\"id\\\",\\\"type\\\":\\\"long\\\",\\\"nullable\\\":true,\\\"metadata\\\":{}},"
          + "{\\\"name\\\":\\\"date\\\",\\\"type\\\":\\\"date\\\",\\\"nullable\\\":true,\\\"metadata\\\":{}}]}";

  @TempDir Path dir;

  private DeltaTableQueryReader reader;
  private ResolvedAsset table;

  @BeforeEach
  void writeTable() throws IOException {
    Path log = Files.createDirectories(dir.resolve("_delta_log"));
    Files.writeString(
        log.resolve("00000000000000000000.json"),
        String.join(
            "\n",
            "{\"protocol\":{\"minReaderVersion\":1,\"minWriterVersion\":2}}",
            "{\"metaData\":{\"id\":\"t\",\"format\":{\"provider\":\"parquet\",\"options\":{}},"
                + "\"schemaString\":\""
                + SCHEMA
                + "\",\"partitionColumns\":[\"date\"],\"configuration\":{},\"createdTime\":0}}",
            add("a", "2021-01-01", 1, 10),
            add("b", "2021-01-02", 11, 20),
            add("c", "2021-01-02", 21, 30))
            + "\n");

    Engine engine = DefaultEngine.create(new Configuration());
    String location = dir.toUri().toString();
    StorageCredentials credentials =
        new StorageCredentials(location, CloudProvider.AWS, Map.of(), Instant.MAX);
    DeltaKernel kernel = mock(DeltaKernel.class);
    when(kernel.open(any(), any(), any()))
        .thenReturn(
            new DeltaKernel.Session(
                engine, Table.forPath(engine, dir.toString()), location, credentials));
    UrlSigners signers = mock(UrlSigners.class);
    when(signers.sign(anyString(), any(), any()))
        .thenAnswer(
            call ->
                new SignedUrl(
                    "https://signed/" + Path.of(call.getArgument(0, String.class)).getFileName(),
                    Instant.ofEpochMilli(4102444800000L)));
    reader = new DeltaTableQueryReader(kernel, new OpenSharingProperties(), signers);
    table =
        ResolvedAsset.builder(AssetType.TABLE, "main.sales.events")
            .storageLocation(location)
            .format(TableFormat.DELTA)
            .build();
  }

  @Test
  void jsonPredicateHintsPrunePartitionsAndSkipFilesByStats() {
    assertEquals(List.of("a", "b", "c"), files(null, null));
    // date = 2021-01-02 prunes the 2021-01-01 partition.
    assertEquals(
        List.of("b", "c"),
        files(
            "{\"op\":\"equal\",\"children\":[{\"op\":\"column\",\"name\":\"date\",\"valueType\":"
                + "\"date\"},{\"op\":\"literal\",\"value\":\"2021-01-02\",\"valueType\":\"date\"}]}",
            null));
    // id > 25 skips files whose maxValues.id is at most 25.
    assertEquals(
        List.of("c"),
        files(
            "{\"op\":\"greaterThan\",\"children\":[{\"op\":\"column\",\"name\":\"id\",\"valueType\":"
                + "\"long\"},{\"op\":\"literal\",\"value\":\"25\",\"valueType\":\"long\"}]}",
            null));
    // An unsupported hint returns every file.
    assertEquals(List.of("a", "b", "c"), files("{\"op\":\"like\"}", null));
  }

  @Test
  void limitHintStopsOnceFilesCoverTheLimit() {
    // limitHint counts stats.numRecords, which file actions carry through.
    assertTrue(ndjson(null, null).contains("\\\"numRecords\\\":10"));
    assertEquals(0, files(null, 0L).size());
    assertEquals(1, files(null, 10L).size());
    assertEquals(2, files(null, 11L).size());
    assertEquals(3, files(null, 1000L).size());
  }

  @Test
  void startingVersionReturnsDataChangeFilesAndHistoricalMetadata() throws IOException {
    writeChanges();
    List<String> lines = changes(1L, 2L, null, false);
    assertEquals("{\"protocol\":{\"minReaderVersion\":1}}", lines.get(0));
    assertTrue(lines.get(1).contains("\"version\":1"), lines.get(1));
    assertEquals(
        List.of("remove a 1", "add d 1", "metaData 2", "add e 2"),
        describe(lines.subList(2, lines.size())));
    assertTrue(lines.get(2).contains("\"timestamp\":"), lines.get(2));
    assertFalse(lines.get(2).contains("\"stats\":"), lines.get(2));
    assertTrue(lines.get(3).contains("\"stats\":"), lines.get(3));

    // Parquet cannot carry the deletion vector protocol committed at version 3.
    assertThrows(ApiException.class, () -> changes(1L, null, null, false));
  }

  @Test
  void includeHistoricalProtocolAddsLaterProtocolsToDeltaResponses() throws IOException {
    writeChanges();
    String delta = "responseformat=delta;readerfeatures=deletionvectors";
    // The compaction at version 4 rewrites files without dataChange, so it adds nothing.
    assertEquals(
        List.of("remove a 1", "add d 1", "metaData 2", "add e 2"),
        describe(changes(1L, null, delta, false).subList(2, 6)));
    List<String> withoutProtocol = changes(1L, null, delta, false);
    assertEquals(6, withoutProtocol.size());
    assertFalse(withoutProtocol.get(0).contains("\"version\""), withoutProtocol.get(0));
    List<String> withProtocol = changes(1L, null, delta, true);
    assertEquals(
        List.of("remove a 1", "add d 1", "metaData 2", "add e 2", "protocol 3"),
        describe(withProtocol.subList(2, withProtocol.size())));
    assertTrue(withProtocol.get(0).contains("\"version\":1"), withProtocol.get(0));
  }

  @Test
  void rejectsVersionsAfterTheLatestVersion() throws IOException {
    writeChanges();
    assertThrows(ApiException.class, () -> changes(5L, null, null, false));
    assertThrows(ApiException.class, () -> changes(1L, 5L, null, false));
  }

  private List<String> changes(
      long startingVersion, Long endingVersion, String capabilities, boolean historicalProtocol) {
    return List.of(
        reader
            .readChanges(
                table,
                null,
                new DeltaTableQueryReader.ResponseOptions(capabilities, null, false),
                startingVersion,
                endingVersion,
                historicalProtocol)
            .ndjson()
            .split("\n"));
  }

  /** "add d 1": action, file name, and version; metaData and protocol lines show their version. */
  private static List<String> describe(List<String> lines) {
    return lines.stream()
        .map(
            line -> {
              JsonNode node = parse(line);
              String key = node.fieldNames().next();
              JsonNode body = node.get(key);
              if (key.equals("file")) {
                JsonNode action = body.get("deltaSingleAction");
                key = action.fieldNames().next();
                String name = fileName(action.get(key).get("path"));
                return key + " " + name + " " + body.get("version");
              }
              if (key.equals("add") || key.equals("remove")) {
                return key + " " + fileName(body.get("url")) + " " + body.get("version");
              }
              return key + " " + body.get("version");
            })
        .toList();
  }

  private static String fileName(JsonNode url) {
    return url.asText().replaceAll(".*https://signed/(\\w)\\.parquet.*", "$1");
  }

  private static JsonNode parse(String line) {
    try {
      return new ObjectMapper().readTree(line);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  // 1: delete a, insert d. 2: change metaData, insert e. 3: enable deletion vectors. 4: compact.
  private void writeChanges() throws IOException {
    Path log = dir.resolve("_delta_log");
    Files.writeString(
        log.resolve("00000000000000000001.json"),
        remove("a", "2021-01-01", true) + "\n" + add("d", "2021-01-03", 31, 40) + "\n");
    Files.writeString(
        log.resolve("00000000000000000002.json"),
        "{\"metaData\":{\"id\":\"t\",\"format\":{\"provider\":\"parquet\",\"options\":{}},"
            + "\"schemaString\":\""
            + SCHEMA
            + "\",\"partitionColumns\":[\"date\"],\"configuration\":{\"k\":\"v\"},"
            + "\"createdTime\":0}}\n"
            + add("e", "2021-01-03", 41, 50)
            + "\n");
    Files.writeString(
        log.resolve("00000000000000000003.json"),
        "{\"protocol\":{\"minReaderVersion\":3,\"minWriterVersion\":7,"
            + "\"readerFeatures\":[\"deletionVectors\"],"
            + "\"writerFeatures\":[\"deletionVectors\"]}}\n");
    Files.writeString(
        log.resolve("00000000000000000004.json"),
        remove("b", "2021-01-02", false)
            + "\n"
            + remove("c", "2021-01-02", false)
            + "\n"
            + add("f", "2021-01-02", 11, 30).replace("\"dataChange\":true", "\"dataChange\":false")
            + "\n");
  }

  private static String remove(String name, String date, boolean dataChange) {
    return ("{\"remove\":{\"path\":\"date=%s/%s.parquet\",\"deletionTimestamp\":1,"
            + "\"dataChange\":%s,\"partitionValues\":{\"date\":\"%s\"},\"size\":1}}")
        .formatted(date, name, dataChange, date);
  }

  private String ndjson(String jsonPredicateHints, Long limitHint) {
    return reader
        .read(
            table,
            null,
            new DeltaTableQueryReader.ResponseOptions(null, null, false),
            null,
            null,
            null,
            false,
            jsonPredicateHints,
            limitHint)
        .ndjson();
  }

  private List<String> files(String jsonPredicateHints, Long limitHint) {
    return Arrays.stream(ndjson(jsonPredicateHints, limitHint).split("\n"))
        .filter(line -> line.startsWith("{\"file\""))
        .map(line -> line.replaceAll(".*https://signed/(\\w)\\.parquet.*", "$1"))
        .sorted()
        .toList();
  }

  private static String add(String name, String date, long minId, long maxId) {
    return ("{\"add\":{\"path\":\"date=%s/%s.parquet\",\"partitionValues\":{\"date\":\"%s\"},"
            + "\"size\":1,\"modificationTime\":0,\"dataChange\":true,\"stats\":\"{\\\"numRecords\\\":10,"
            + "\\\"minValues\\\":{\\\"id\\\":%d},\\\"maxValues\\\":{\\\"id\\\":%d},"
            + "\\\"nullCount\\\":{\\\"id\\\":0}}\"}}")
        .formatted(date, name, date, minId, maxId);
  }
}
