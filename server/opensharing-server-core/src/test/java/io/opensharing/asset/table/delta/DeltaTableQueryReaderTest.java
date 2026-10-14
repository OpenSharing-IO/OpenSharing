package io.opensharing.asset.table.delta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    StringBuilder ndjson = new StringBuilder();
    reader.read(
        table,
        null,
        new DeltaTableQueryReader.ResponseOptions(null, null, false),
        new DeltaTableQueryReader.ResponseSink() {
          @Override
          public void begin(long version, ResponseFormat format) {}

          @Override
          public void line(String line) {
            ndjson.append(line);
          }
        },
        null,
        null,
        null,
        false,
        jsonPredicateHints,
        limitHint);
    return ndjson.toString();
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
}
