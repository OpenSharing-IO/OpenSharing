package io.opensharing.asset.table;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.delta.kernel.defaults.internal.json.JsonUtils;
import io.delta.kernel.internal.actions.Format;
import io.delta.kernel.internal.actions.Metadata;
import io.delta.kernel.internal.actions.Protocol;
import io.delta.kernel.internal.util.VectorUtils;
import io.opensharing.asset.table.DeltaSharingCapabilities.ResponseFormat;
import io.opensharing.catalog.ResolvedAsset;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Protocol and metadata NDJSON actions shared by Query Table Metadata and Query Table. */
public final class TableActions {

  private static final ObjectMapper JSON =
      new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

  private TableActions() {}

  public static String protocol(Protocol protocol, Long version, ResponseFormat format) {
    return line(
        new ProtocolLine(
            format == ResponseFormat.DELTA
                ? new DeltaProtocol(version, protocol)
                : new ParquetProtocol(protocol.getMinReaderVersion())));
  }

  public static String metadata(
      Metadata metadata,
      ResolvedAsset table,
      Long version,
      Long size,
      Long numFiles,
      ResponseFormat format,
      boolean historyShared) {
    if (format == ResponseFormat.DELTA) {
      return line(
          new MetadataLine(
              new DeltaMetadata(
                  version,
                  size,
                  numFiles,
                  location(table),
                  auxiliaryLocations(table),
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
                location(table),
                auxiliaryLocations(table),
                TableAccessModes.forTable(table),
                new FormatBody(tableFormat.getProvider()),
                metadata.getSchemaString(),
                VectorUtils.toJavaList(metadata.getPartitionColumns()),
                parquetConfiguration(metadata.getConfiguration(), historyShared),
                version,
                size,
                numFiles)));
  }

  private static String line(Object value) {
    try {
      return JSON.writeValueAsString(value) + "\n";
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("failed to encode table action", e);
    }
  }

  private static String location(ResolvedAsset table) {
    return table.location() == null ? null : table.location().storageLocation();
  }

  private static List<String> auxiliaryLocations(ResolvedAsset table) {
    return table.location() == null || table.location().auxiliaryLocations().isEmpty()
        ? null
        : table.location().auxiliaryLocations();
  }

  /**
   * Parquet metadata keeps only {@code enableChangeDataFeed}, rewritten from {@code
   * delta.enableChangeDataFeed}, when that property is true and CDF or table history is shared.
   * Every other table property is dropped.
   */
  private static Map<String, String> parquetConfiguration(
      Map<String, String> configuration, boolean historyShared) {
    if (historyShared
        && configuration != null
        && "true".equalsIgnoreCase(configuration.get("delta.enableChangeDataFeed"))) {
      return Map.of("enableChangeDataFeed", "true");
    }
    return Map.of();
  }

  /** Writes Kernel protocol/metadata rows as Delta log JSON. */
  public static final class KernelActionSerializer extends JsonSerializer<Object> {
    @Override
    public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers)
        throws IOException {
      gen.writeRawValue(
          JsonUtils.rowToJson(
              value instanceof Protocol protocol
                  ? protocol.toRow()
                  : ((Metadata) value).toRow()));
    }
  }

  /**
   * The protocol line of a response:
   *
   * <pre>{@code
   * {"protocol": {...}}
   * }</pre>
   */
  public record ProtocolLine(ProtocolBody protocol) {}

  public sealed interface ProtocolBody permits ParquetProtocol, DeltaProtocol {}

  /**
   * The protocol in a parquet response:
   *
   * <pre>{@code
   * {"minReaderVersion": 1}
   * }</pre>
   */
  public record ParquetProtocol(int minReaderVersion) implements ProtocolBody {}

  /**
   * The protocol in a delta response, carrying the log's protocol action:
   *
   * <pre>{@code
   * {"version": 1,
   *  "deltaProtocol": {"minReaderVersion": 3, "minWriterVersion": 7,
   *                    "readerFeatures": ["deletionVectors"], "writerFeatures": [...]}}
   * }</pre>
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record DeltaProtocol(
      Long version,
      @JsonSerialize(using = KernelActionSerializer.class) Protocol deltaProtocol)
      implements ProtocolBody {}

  /**
   * The metadata line of a response:
   *
   * <pre>{@code
   * {"metaData": {...}}
   * }</pre>
   */
  public record MetadataLine(MetadataBody metaData) {}

  public sealed interface MetadataBody permits ParquetMetadata, DeltaMetadata {}

  /**
   * The metadata in a parquet response:
   *
   * <pre>{@code
   * {"id": "<table id>", "name": "<name>", "description": "<description>",
   *  "location": "s3://bucket/table", "auxiliaryLocations": ["s3://bucket/other"],
   *  "accessModes": ["url", "dir"], "format": {"provider": "parquet"},
   *  "schemaString": "<schema json>", "partitionColumns": ["date"],
   *  "configuration": {"enableChangeDataFeed": "true"}, "version": 1, "size": 573, "numFiles": 1}
   * }</pre>
   */
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

  /**
   * The metadata in a delta response, carrying the log's metaData action:
   *
   * <pre>{@code
   * {"version": 1, "size": 573, "numFiles": 1, "location": "s3://bucket/table",
   *  "auxiliaryLocations": ["s3://bucket/other"], "accessModes": ["url", "dir"],
   *  "deltaMetadata": {"id": "<table id>", "format": {"provider": "parquet", "options": {}},
   *                    "schemaString": "<schema json>", "partitionColumns": [], ...}}
   * }</pre>
   */
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

  /**
   * The data file format of a parquet metadata:
   *
   * <pre>{@code
   * {"provider": "parquet"}
   * }</pre>
   */
  public record FormatBody(String provider) {}
}
