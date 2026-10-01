package io.opensharing.asset.table.delta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
  void sqlPredicateHintsPrunePartitionsAndCombineWithJsonHints() {
    assertEquals(List.of("a"), files(List.of("(date < DATE '2021-01-02')"), null, null));
    // SQL hints only prune partitions, so a data-column hint is dropped.
    assertEquals(List.of("a", "b", "c"), files(List.of("(id > 25L)"), null, null));
    assertEquals(
        List.of("c"),
        files(
            List.of("(date = DATE '2021-01-02')"),
            "{\"op\":\"greaterThan\",\"children\":[{\"op\":\"column\",\"name\":\"id\",\"valueType\":"
                + "\"long\"},{\"op\":\"literal\",\"value\":\"25\",\"valueType\":\"long\"}]}",
            null));
  }

  @Test
  void limitHintStopsOnceFilesCoverTheLimit() {
    // limitHint counts stats.numRecords, which file actions carry through.
    assertTrue(ndjson(null, null, null).contains("\\\"numRecords\\\":10"));
    assertEquals(0, files(null, 0L).size());
    assertEquals(1, files(null, 10L).size());
    assertEquals(2, files(null, 11L).size());
    assertEquals(3, files(null, 1000L).size());
  }

  private String ndjson(
      List<String> predicateHints, String jsonPredicateHints, Long limitHint) {
    return reader
        .read(
            table,
            null,
            null,
            null,
            null,
            null,
            false,
            false,
            false,
            predicateHints,
            jsonPredicateHints,
            limitHint)
        .ndjson();
  }

  private List<String> files(String jsonPredicateHints, Long limitHint) {
    return files(null, jsonPredicateHints, limitHint);
  }

  private List<String> files(
      List<String> predicateHints, String jsonPredicateHints, Long limitHint) {
    return Arrays.stream(ndjson(predicateHints, jsonPredicateHints, limitHint).split("\n"))
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
