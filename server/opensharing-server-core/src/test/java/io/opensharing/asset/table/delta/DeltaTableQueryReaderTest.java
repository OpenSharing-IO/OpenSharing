package io.opensharing.asset.table.delta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.hadoop.conf.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Query Table on local Delta logs. Kernel opens each log directly and every path signs to {@code
 * https://signed/<file name>}, so no catalog or cloud storage is involved.
 */
class DeltaTableQueryReaderTest {

  private static final String OUTSIDE = "the table references a file outside its storage location";
  private static final Pattern SIGNED = Pattern.compile("https://signed/[^\"]+");

  @TempDir Path dir;

  private int tables;

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
            false);
    return urls;
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
