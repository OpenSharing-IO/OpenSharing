package io.opensharing.asset.table;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.delta.kernel.data.Row;
import io.delta.kernel.defaults.internal.json.JsonUtils;
import io.delta.kernel.internal.actions.AddFile;
import io.delta.kernel.internal.actions.DeletionVectorDescriptor;
import io.delta.kernel.internal.actions.Format;
import io.delta.kernel.internal.actions.Metadata;
import io.delta.kernel.internal.actions.Protocol;
import io.delta.kernel.internal.data.DelegateRow;
import io.delta.kernel.internal.util.VectorUtils;
import io.opensharing.asset.table.DeltaSharingCapabilities.ResponseFormat;
import io.opensharing.catalog.ResolvedAsset;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Protocol, metadata, and file NDJSON actions shared by Query Table Metadata and Query Table. */
public final class TableActions {

  private static final ObjectMapper JSON =
      new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

  private TableActions() {}

  public static String protocol(Protocol protocol, Long version, ResponseFormat format) {
    return line(
        new ProtocolLine(
            format == ResponseFormat.DELTA
                ? new DeltaProtocol(version, protocol)
                : new ParquetProtocol(1)));
  }

  public static String metadata(
      Metadata metadata,
      ResolvedAsset table,
      Long version,
      Long size,
      Long numFiles,
      ResponseFormat format) {
    if (format == ResponseFormat.DELTA) {
      return line(
          new MetadataLine(
              new DeltaMetadata(
                  version,
                  size,
                  numFiles,
                  table.storageLocation(),
                  emptyToNull(table.auxiliaryLocations()),
                  TableAccessModes.forTable(table),
                  metadata)));
    }
    Format tableFormat = metadata.getFormat();
    return line(
        new MetadataLine(
            new ParquetMetadata(
                metadata.getId(),
                metadata.getName().orElse(null),
                metadata.getDescription().orElse(null),
                table.storageLocation(),
                emptyToNull(table.auxiliaryLocations()),
                TableAccessModes.forTable(table),
                new FormatBody(tableFormat.getProvider()),
                metadata.getSchemaString(),
                VectorUtils.toJavaList(metadata.getPartitionColumns()),
                emptyMapToNull(metadata.getConfiguration()),
                version,
                size,
                numFiles)));
  }

  public static String parquetFile(
      String url,
      String id,
      Map<String, String> partitionValues,
      long size,
      String stats,
      Long version,
      Long timestamp,
      Long expirationTimestamp) {
    return line(
        new FileLine(
            new ParquetFile(
                url,
                id,
                partitionValues == null ? Map.of() : partitionValues,
                size,
                stats,
                version,
                timestamp,
                expirationTimestamp)));
  }

  public static String deltaFile(
      String id,
      String deletionVectorFileId,
      Long expirationTimestamp,
      Long version,
      Long timestamp,
      AddFile add) {
    return line(
        new FileLine(
            new DeltaFile(
                id,
                deletionVectorFileId,
                version,
                timestamp,
                expirationTimestamp,
                new DeltaSingleAction(add))));
  }

  public static String endStreamAction(
      String refreshToken, String nextPageToken, Long minUrlExpirationTimestamp) {
    return line(
        new EndStreamLine(
            new EndStreamAction(refreshToken, nextPageToken, minUrlExpirationTimestamp)));
  }

  /**
   * Replace the add path with a signed file URL. When {@code deletionVectorUrl} is set, rewrite the
   * on-disk deletion vector as a path-type descriptor so clients fetch the signed object.
   */
  public static AddFile withPath(AddFile add, String path, String deletionVectorUrl) {
    Row row = add.toRow();
    Map<Integer, Object> overrides = new HashMap<>();
    overrides.put(row.getSchema().indexOf("path"), path);
    if (deletionVectorUrl != null) {
      int dvOrdinal = row.getSchema().indexOf("deletionVector");
      Row dv = row.getStruct(dvOrdinal);
      overrides.put(
          dvOrdinal,
          new DelegateRow(
              dv,
              Map.of(
                  dv.getSchema().indexOf("storageType"),
                  (Object) DeletionVectorDescriptor.PATH_DV_MARKER,
                  dv.getSchema().indexOf("pathOrInlineDv"),
                  deletionVectorUrl)));
    }
    return new AddFile(new DelegateRow(row, overrides));
  }

  private static String line(Object value) {
    try {
      return JSON.writeValueAsString(value) + "\n";
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("failed to encode table action", e);
    }
  }

  private static List<String> emptyToNull(List<String> values) {
    return values == null || values.isEmpty() ? null : values;
  }

  private static Map<String, String> emptyMapToNull(Map<String, String> values) {
    return values == null || values.isEmpty() ? null : values;
  }

  /** Writes Kernel protocol/metadata rows as Delta log JSON. */
  public static final class KernelActionSerializer extends JsonSerializer<Object> {
    @Override
    public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers)
        throws IOException {
      Row row =
          switch (value) {
            case Protocol protocol -> protocol.toRow();
            case Metadata metadata -> metadata.toRow();
            case AddFile add -> add.toRow();
            default ->
                throw new IllegalArgumentException(
                    "unsupported Kernel action: " + value.getClass().getName());
          };
      gen.writeRawValue(JsonUtils.rowToJson(row));
    }
  }

  public record ProtocolLine(ProtocolBody protocol) {}

  public sealed interface ProtocolBody permits ParquetProtocol, DeltaProtocol {}

  public record ParquetProtocol(int minReaderVersion) implements ProtocolBody {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record DeltaProtocol(
      Long version,
      @JsonSerialize(using = KernelActionSerializer.class) Protocol deltaProtocol)
      implements ProtocolBody {}

  public record MetadataLine(MetadataBody metaData) {}

  public sealed interface MetadataBody permits ParquetMetadata, DeltaMetadata {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ParquetMetadata(
      String id,
      String name,
      String description,
      String location,
      List<String> auxiliaryLocations,
      List<String> accessModes,
      FormatBody format,
      String schemaString,
      List<String> partitionColumns,
      Map<String, String> configuration,
      Long version,
      Long size,
      Long numFiles)
      implements MetadataBody {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record DeltaMetadata(
      Long version,
      Long size,
      Long numFiles,
      String location,
      List<String> auxiliaryLocations,
      List<String> accessModes,
      @JsonSerialize(using = KernelActionSerializer.class) Metadata deltaMetadata)
      implements MetadataBody {}

  public record FormatBody(String provider) {}

  public record FileLine(FileBody file) {}

  public sealed interface FileBody permits ParquetFile, DeltaFile {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ParquetFile(
      String url,
      String id,
      Map<String, String> partitionValues,
      long size,
      String stats,
      Long version,
      Long timestamp,
      Long expirationTimestamp)
      implements FileBody {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record DeltaFile(
      String id,
      String deletionVectorFileId,
      Long version,
      Long timestamp,
      Long expirationTimestamp,
      DeltaSingleAction deltaSingleAction)
      implements FileBody {}

  public record DeltaSingleAction(
      @JsonSerialize(using = KernelActionSerializer.class) AddFile add) {}

  public record EndStreamLine(EndStreamAction endStreamAction) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record EndStreamAction(
      String refreshToken, String nextPageToken, Long minUrlExpirationTimestamp) {}
}
