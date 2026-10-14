package io.opensharing.asset.table.delta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.delta.kernel.Table;
import io.delta.kernel.defaults.engine.DefaultEngine;
import io.delta.kernel.engine.Engine;
import io.opensharing.asset.table.DeltaSharingCapabilities.ResponseFormat;
import io.opensharing.asset.table.signer.SignedUrl;
import io.opensharing.asset.table.signer.SigningSession;
import io.opensharing.asset.table.signer.UrlSigners;
import io.opensharing.auth.AuthContext;
import io.opensharing.catalog.AssetLocation;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.DataSourceFormat;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.catalog.TableProperties;
import io.opensharing.exception.CatalogException;
import io.opensharing.http.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.hadoop.conf.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Query Table on local Delta logs. Kernel opens each log directly and every path signs to {@code
 * https://signed/<file name>}, so no catalog or cloud storage is involved.
 */
class DeltaTableQueryReaderTest {

  private static final String OUTSIDE = "the table references a file outside its storage location";
  private static final Pattern SIGNED = Pattern.compile("https://signed/[^\"]+");
  private static final String SCHEMA =
      "{\\\"type\\\":\\\"struct\\\",\\\"fields\\\":["
          + "{\\\"name\\\":\\\"id\\\",\\\"type\\\":\\\"long\\\",\\\"nullable\\\":true,"
          + "\\\"metadata\\\":{}},"
          + "{\\\"name\\\":\\\"date\\\",\\\"type\\\":\\\"date\\\",\\\"nullable\\\":true,"
          + "\\\"metadata\\\":{}}]}";

  @TempDir Path dir;

  private int tables;
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

    reader = reader(dir);
    table = table(dir);
  }

  @Test
  void signsOnlyFilesUnderTheTableRoot() throws IOException {
    assertEquals(List.of("https://signed/a.parquet"), signedUrls("a.parquet", null));

    // A sibling whose name starts with the root's, a path up out of the root, another bucket.
    String sibling = dir.resolve("t1-other/b.parquet").toUri().toString();
    assertOutside(() -> signedUrls(sibling, null));
    assertOutside(() -> signedUrls("../elsewhere/b.parquet", null));
    assertOutside(() -> signedUrls("s3://other-bucket/t/b.parquet", null));
  }

  @Test
  void signsOnlyDeletionVectorsUnderTheTableRoot() {
    String outside = dir.resolve("elsewhere/dv.bin").toUri().toString();
    assertOutside(() -> signedUrls("a.parquet", outside));
  }

  @Test
  void jsonPredicateHintsPrunePartitionsAndSkipFilesByStats() {
    assertEquals(List.of("a", "b", "c"), files(null, null));
    // date = 2021-01-02 prunes the 2021-01-01 partition.
    assertEquals(
        List.of("b", "c"),
        files(
            "{\"op\":\"equal\",\"children\":[{\"op\":\"column\",\"name\":\"date\","
                + "\"valueType\":\"date\"},{\"op\":\"literal\",\"value\":\"2021-01-02\","
                + "\"valueType\":\"date\"}]}",
            null));
    // id > 25 skips files whose maxValues.id is at most 25.
    assertEquals(
        List.of("c"),
        files(
            "{\"op\":\"greaterThan\",\"children\":[{\"op\":\"column\",\"name\":\"id\","
                + "\"valueType\":\"long\"},{\"op\":\"literal\",\"value\":\"25\","
                + "\"valueType\":\"long\"}]}",
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
  void limitHintCountsAFileWithADeletionVectorButNoStatsAsNoRecords() throws IOException {
    // Commit 1 replaces file a with a copy that has a deletion vector of 4 rows and no stats.
    Files.writeString(
        dir.resolve("_delta_log/00000000000000000001.json"),
        "{\"remove\":{\"path\":\"date=2021-01-01/a.parquet\",\"dataChange\":true}}\n"
            + "{\"add\":{\"path\":\"date=2021-01-01/a.parquet\","
            + "\"partitionValues\":{\"date\":\"2021-01-01\"},\"size\":1,"
            + "\"modificationTime\":0,\"dataChange\":true,\"deletionVector\":{"
            + "\"storageType\":\"i\",\"pathOrInlineDv\":\"wi5b=000010000siXQKl0rr91000f\","
            + "\"sizeInBytes\":40,\"cardinality\":4}}}\n");
    assertEquals(List.of("a", "b"), files(null, 10L));
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

  @Test
  void changeDataFeedPrefersCdcFilesAndSkipsNoOpMerges() throws IOException {
    writeChangeDataFeed();
    List<String> lines = changeDataFeed(0L, null, 3L, null, null, false);
    // Without includeHistoricalMetadata, protocol and metaData come from the latest version.
    assertTrue(lines.get(1).contains("\"version\":4"), lines.get(1));
    assertEquals(
        List.of("add a 0", "add b 0", "cdf c 1", "remove b 2"),
        describe(lines.subList(2, lines.size())));
    assertTrue(lines.get(4).startsWith("{\"cdf\":"), lines.get(4));

    List<String> delta = changeDataFeed(1L, null, 2L, null, "responseformat=delta", false);
    assertEquals(List.of("cdc c 1", "remove b 2"), describe(delta.subList(2, delta.size())));
  }

  @Test
  void changeDataFeedResolvesTimestampsAndHistoricalMetadata() throws IOException {
    writeChangeDataFeed();
    List<String> lines =
        changeDataFeed(
            null,
            Instant.ofEpochMilli(1500),
            null,
            Instant.ofEpochMilli(3500),
            null,
            true);
    assertTrue(lines.get(1).contains("\"version\":1"), lines.get(1));
    assertEquals(List.of("cdf c 1", "remove b 2"), describe(lines.subList(2, lines.size())));
    // An end after the latest version is capped; the range then reaches CDF being disabled.
    assertThrows(ApiException.class, () -> changeDataFeed(0L, null, 9L, null, null, false));
  }

  @Test
  void changeDataFeedRejectsInvalidRanges() throws IOException {
    // The table written in setup never enabled the Change Data Feed.
    assertThrows(ApiException.class, () -> changeDataFeed(0L, null, null, null, null, false));
    writeChangeDataFeed();
    assertThrows(ApiException.class, () -> changeDataFeed(null, null, null, null, null, false));
    assertThrows(ApiException.class, () -> changeDataFeed(2L, null, 1L, null, null, false));
    assertThrows(ApiException.class, () -> changeDataFeed(5L, null, null, null, null, false));
  }

  private List<String> changeDataFeed(
      Long startingVersion,
      Instant startingTimestamp,
      Long endingVersion,
      Instant endingTimestamp,
      String capabilities,
      boolean includeHistoricalMetadata) {
    return List.of(
        reader
            .readChangeDataFeed(
                table,
                null,
                new DeltaTableQueryReader.ResponseOptions(capabilities, null, false),
                startingVersion,
                startingTimestamp,
                endingVersion,
                endingTimestamp,
                includeHistoricalMetadata,
                false)
            .ndjson()
            .split("\n"));
  }

  // 0: insert a, b. 1: update a to d with a cdc file. 2: delete b without cdc. 3: no-op MERGE.
  // 4: disable the Change Data Feed. Commit n is written at (n + 1) seconds.
  private void writeChangeDataFeed() throws IOException {
    Path log = dir.resolve("_delta_log");
    String metadata =
        "{\"metaData\":{\"id\":\"t\",\"format\":{\"provider\":\"parquet\",\"options\":{}},"
            + "\"schemaString\":\""
            + SCHEMA
            + "\",\"partitionColumns\":[\"date\"],"
            + "\"configuration\":{\"delta.enableChangeDataFeed\":\"%s\"},\"createdTime\":0}}";
    List<String> commits =
        List.of(
            String.join(
                "\n",
                "{\"protocol\":{\"minReaderVersion\":1,\"minWriterVersion\":4}}",
                metadata.formatted("true"),
                add("a", "2021-01-01", 1, 10),
                add("b", "2021-01-02", 11, 20)),
            String.join(
                "\n",
                remove("a", "2021-01-01", true),
                add("d", "2021-01-01", 1, 10),
                "{\"cdc\":{\"path\":\"_change_data/date=2021-01-01/c.parquet\","
                    + "\"partitionValues\":{\"date\":\"2021-01-01\"},\"size\":1,"
                    + "\"dataChange\":false}}"),
            remove("b", "2021-01-02", true),
            String.join(
                "\n",
                "{\"commitInfo\":{\"timestamp\":4000,\"engineInfo\":\"test\","
                    + "\"operation\":\"MERGE\",\"operationParameters\":{},"
                    + "\"isBlindAppend\":false,\"txnId\":\"t\","
                    + "\"operationMetrics\":{\"numTargetRowsInserted\":\"0\","
                    + "\"numTargetRowsUpdated\":\"0\",\"numTargetRowsDeleted\":\"0\"}}}",
                remove("d", "2021-01-01", true),
                add("e", "2021-01-01", 1, 10)),
            metadata.formatted("false"));
    for (int version = 0; version < commits.size(); version++) {
      Path commit = log.resolve("%020d.json".formatted(version));
      Files.writeString(commit, commits.get(version) + "\n");
      Files.setLastModifiedTime(commit, FileTime.fromMillis((version + 1) * 1000L));
    }
  }

  private List<String> changes(
      long startingVersion, Long endingVersion, String capabilities, boolean historicalProtocol) {
    Lines lines = new Lines();
    reader.readChanges(
        table,
        null,
        new DeltaTableQueryReader.ResponseOptions(capabilities, null, false),
        lines,
        startingVersion,
        endingVersion,
        historicalProtocol);
    return List.of(lines.ndjson.toString().split("\n"));
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
              if (key.equals("add") || key.equals("remove") || key.equals("cdf")) {
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

  private static void assertOutside(ThrowingQuery query) {
    assertEquals(OUTSIDE, assertThrows(CatalogException.class, query::run).getMessage());
  }

  /**
   * The signed URLs of a new table whose only file is {@code path}, with an absolute-path deletion
   * vector at {@code deletionVector} if set. A delta response is requested so it is signed.
   */
  private List<String> signedUrls(String path, String deletionVector) throws IOException {
    Path root = dir.resolve("t" + tables++);
    String protocol =
        deletionVector == null
            ? "{\"protocol\":{\"minReaderVersion\":1,\"minWriterVersion\":2}}"
            : "{\"protocol\":{\"minReaderVersion\":3,\"minWriterVersion\":7,"
                + "\"readerFeatures\":[\"deletionVectors\"],"
                + "\"writerFeatures\":[\"deletionVectors\"]}}";
    String dv =
        deletionVector == null
            ? ""
            : ",\"deletionVector\":{\"storageType\":\"p\",\"pathOrInlineDv\":\""
                + deletionVector
                + "\",\"offset\":1,\"sizeInBytes\":36,\"cardinality\":2}";
    Files.createDirectories(root.resolve("_delta_log"));
    Files.writeString(
        root.resolve("_delta_log/00000000000000000000.json"),
        String.join(
                "\n",
                protocol,
                "{\"metaData\":{\"id\":\"t\",\"format\":{\"provider\":\"parquet\",\"options\":{}},"
                    + "\"schemaString\":\"{\\\"type\\\":\\\"struct\\\",\\\"fields\\\":[]}\","
                    + "\"partitionColumns\":[],\"configuration\":{},\"createdTime\":0}}",
                "{\"add\":{\"path\":\""
                    + path
                    + "\",\"partitionValues\":{},\"size\":1,\"modificationTime\":0,"
                    + "\"dataChange\":true"
                    + dv
                    + "}}")
            + "\n");

    List<String> urls = new ArrayList<>();
    reader(root)
        .read(
            table(root),
            null,
            new DeltaTableQueryReader.ResponseOptions(
                "responseformat=delta;readerfeatures=deletionvectors", null, false),
            new DeltaTableQueryReader.ResponseSink() {
              @Override
              public void begin(long version, ResponseFormat format) {}

              @Override
              public void line(String line) {
                Matcher signed = SIGNED.matcher(line);
                while (signed.find()) {
                  urls.add(signed.group());
                }
              }
            },
            null,
            null,
            null,
            false,
            null,
            null);
    return urls;
  }

  private String ndjson(String jsonPredicateHints, Long limitHint) {
    Lines lines = new Lines();
    reader.read(
        table,
        null,
        new DeltaTableQueryReader.ResponseOptions(null, null, false),
        lines,
        null,
        null,
        null,
        false,
        jsonPredicateHints,
        limitHint);
    return lines.ndjson.toString();
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
            + "\"size\":1,\"modificationTime\":0,\"dataChange\":true,"
            + "\"stats\":\"{\\\"numRecords\\\":10,"
            + "\\\"minValues\\\":{\\\"id\\\":%d},\\\"maxValues\\\":{\\\"id\\\":%d},"
            + "\\\"nullCount\\\":{\\\"id\\\":0}}\"}}")
        .formatted(date, name, date, minId, maxId);
  }

  private static DeltaTableQueryReader reader(Path root) {
    String location = root.toUri().toString();
    StorageCredentials credentials =
        new StorageCredentials(location, CloudProvider.AWS, Map.of(), Instant.MAX);
    DeltaKernel kernel =
        new DeltaKernel(null) {
          @Override
          public Session open(ResolvedAsset table, AuthContext auth, Duration ttl) {
            SessionFileIO files = new SessionFileIO(new Configuration(), root.toString());
            Engine engine = DefaultEngine.create(files);
            return new Session(
                engine, Table.forPath(engine, root.toString()), location, credentials, files);
          }
        };
    UrlSigners signers =
        new UrlSigners() {
          @Override
          public SigningSession open(StorageCredentials credentials) {
            return (path, ttl) ->
                new SignedUrl(
                    "https://signed/" + path.substring(path.lastIndexOf('/') + 1),
                    Instant.ofEpochMilli(4102444800000L));
          }
        };
    return new DeltaTableQueryReader(kernel, signers);
  }

  private static ResolvedAsset table(Path root) {
    return ResolvedAsset.builder(AssetType.TABLE, "main.sales.t")
        .location(new AssetLocation(root.toUri().toString(), null))
        .additionalProperties(new TableProperties(DataSourceFormat.DELTA, Map.of()))
        .build();
  }

  private interface ThrowingQuery {
    void run() throws IOException;
  }

  private static final class Lines implements DeltaTableQueryReader.ResponseSink {

    private final StringBuilder ndjson = new StringBuilder();

    @Override
    public void begin(long version, ResponseFormat format) {}

    @Override
    public void line(String line) {
      ndjson.append(line);
    }
  }
}
