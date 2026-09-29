package io.opensharing.asset.table;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.delta.kernel.defaults.internal.json.JsonUtils;
import io.delta.kernel.internal.actions.AddFile;
import io.delta.kernel.internal.actions.Format;
import io.delta.kernel.internal.actions.Metadata;
import io.delta.kernel.internal.actions.Protocol;
import io.delta.kernel.internal.util.VectorUtils;
import io.delta.kernel.types.StringType;
import io.opensharing.asset.table.DeltaSharingCapabilities.ResponseFormat;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.TableFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TableActionsTest {

  private static final String SCHEMA =
      "{\"type\":\"struct\",\"fields\":[{\"name\":\"eventTime\",\"type\":\"timestamp\",\"nullable\":true,\"metadata\":{}},{\"name\":\"date\",\"type\":\"date\",\"nullable\":true,\"metadata\":{}}]}";

  private static final ResolvedAsset TABLE =
      ResolvedAsset.builder(AssetType.TABLE, "main.sales.table1")
          .format(TableFormat.DELTA)
          .subtype("MANAGED")
          .storageLocation("s3://delta-share-demo/tables/table1")
          .auxiliaryLocations(List.of("s3://delta-share-demo/tables/table1-aux1"))
          .build();

  @Test
  void encodesParquetProtocolAndMetadata() {
    assertEquals(parquetBody(""), encode(ResponseFormat.PARQUET, null, null, null));
    assertEquals(parquetBody(",\"version\":20"), encode(ResponseFormat.PARQUET, 20L, null, null));
  }

  @Test
  void encodesParquetAndDeltaFiles() {
    assertEquals(
        """
        {"file":{"url":"https://example.invalid/part.parquet","id":"file-1","partitionValues":{"date":"2021-04-28"},"size":573,"stats":"{\\"numRecords\\":1}","expirationTimestamp":1652140800000}}
        """,
        TableActions.parquetFile(
            "https://example.invalid/part.parquet",
            "file-1",
            Map.of("date", "2021-04-28"),
            573,
            "{\"numRecords\":1}",
            null,
            null,
            1652140800000L));
    assertEquals(
        """
        {"file":{"url":"https://example.invalid/part.parquet","id":"file-1","partitionValues":{},"size":1,"expirationTimestamp":1}}
        """,
        TableActions.parquetFile(
            "https://example.invalid/part.parquet", "file-1", Map.of(), 1, null, null, null, 1L));
    AddFile add =
        new AddFile(
            AddFile.createAddFileRow(
                AddFile.FULL_SCHEMA,
                "https://example.invalid/part.parquet",
                VectorUtils.stringStringMapValue(Map.of("date", "2021-04-28")),
                573,
                1,
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
    String addJson = JsonUtils.rowToJson(add.toRow());
    assertEquals(
        """
        {"file":{"id":"file-1","size":573,"expirationTimestamp":1652140800000,"deltaSingleAction":{"add":%s}}}
        """
            .formatted(addJson),
        TableActions.deltaFile("file-1", 573L, 1652140800000L, null, null, add));
  }

  @Test
  void encodesDeltaProtocolAndMetadataFromKernel() {
    Protocol protocol = protocol();
    Metadata metadata = metadata();
    String deltaProtocol = JsonUtils.rowToJson(protocol.toRow());
    String deltaMetadata = JsonUtils.rowToJson(metadata.toRow());
    assertEquals(
        deltaBody(deltaProtocol, "", deltaMetadata), encode(ResponseFormat.DELTA, null, null, null));
    assertEquals(
        deltaBody(deltaProtocol, "\"size\":123456,\"numFiles\":5,", deltaMetadata),
        encode(ResponseFormat.DELTA, null, 123456L, 5L));
    assertEquals(
        """
        {"protocol":{"version":5,"deltaProtocol":%s}}
        """
            .formatted(deltaProtocol),
        TableActions.protocol(protocol, 5L, ResponseFormat.DELTA));
  }

  private static String encode(ResponseFormat format, Long version, Long size, Long numFiles) {
    return TableActions.protocol(protocol(), null, format)
        + TableActions.metadata(metadata(), TABLE, version, size, numFiles, format);
  }

  private static String parquetBody(String versionField) {
    return """
        {"protocol":{"minReaderVersion":1}}
        {"metaData":{"id":"f8d5c169-3d01-4ca3-ad9e-7dc3355aedb2","location":"s3://delta-share-demo/tables/table1","auxiliaryLocations":["s3://delta-share-demo/tables/table1-aux1"],"accessModes":["url","dir"],"format":{"provider":"parquet"},"schemaString":"{\\"type\\":\\"struct\\",\\"fields\\":[{\\"name\\":\\"eventTime\\",\\"type\\":\\"timestamp\\",\\"nullable\\":true,\\"metadata\\":{}},{\\"name\\":\\"date\\",\\"type\\":\\"date\\",\\"nullable\\":true,\\"metadata\\":{}}]}","partitionColumns":["date"],"configuration":{"enableChangeDataFeed":"true"}%s}}
        """
        .formatted(versionField);
  }

  private static String deltaBody(String deltaProtocol, String stats, String deltaMetadata) {
    return """
        {"protocol":{"deltaProtocol":%s}}
        {"metaData":{%s"location":"s3://delta-share-demo/tables/table1","auxiliaryLocations":["s3://delta-share-demo/tables/table1-aux1"],"accessModes":["url","dir"],"deltaMetadata":%s}}
        """
        .formatted(deltaProtocol, stats, deltaMetadata);
  }

  private static Protocol protocol() {
    return new Protocol(3, 7, Set.of("columnMapping"), Set.of("columnMapping", "identityColumns"));
  }

  private static Metadata metadata() {
    return new Metadata(
        "f8d5c169-3d01-4ca3-ad9e-7dc3355aedb2",
        Optional.empty(),
        Optional.empty(),
        new Format(),
        SCHEMA,
        null,
        VectorUtils.buildArrayValue(List.of("date"), StringType.STRING),
        Optional.empty(),
        VectorUtils.stringStringMapValue(Map.of("enableChangeDataFeed", "true")));
  }
}
