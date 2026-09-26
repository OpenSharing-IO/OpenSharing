package io.opensharing.asset.table;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.delta.kernel.Snapshot;
import io.delta.kernel.exceptions.KernelException;
import io.delta.kernel.exceptions.TableNotFoundException;
import io.delta.kernel.internal.SnapshotImpl;
import io.delta.kernel.internal.actions.Format;
import io.delta.kernel.internal.actions.Metadata;
import io.delta.kernel.internal.actions.Protocol;
import io.delta.kernel.internal.util.VectorUtils;
import io.opensharing.auth.AuthContext;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.TableFormat;
import io.opensharing.http.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * Reads Query Table Metadata from a Delta log. The parquet or delta response is two NDJSON lines:
 * protocol, then metaData.
 */
@Component
public class DeltaTableMetadataReader {

  private static final ObjectMapper JSON =
      new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

  private final DeltaKernel kernel;

  public DeltaTableMetadataReader(DeltaKernel kernel) {
    this.kernel = kernel;
  }

  public Result read(
      ResolvedAsset table,
      Long version,
      Instant timestamp,
      AuthContext auth,
      String capabilities) {
    if (version != null && timestamp != null) {
      throw ApiException.invalidParameter("version and timestamp are mutually exclusive");
    }
    DeltaKernel.Session session = kernel.open(table, auth);
    Snapshot snapshot = snapshot(session, version, timestamp);
    SnapshotImpl impl = (SnapshotImpl) snapshot;
    boolean historical = version != null || timestamp != null;
    try {
      return new Result(
          snapshot.getVersion(),
          ndjson(
              impl.getProtocol(),
              impl.getMetadata(),
              table,
              historical ? snapshot.getVersion() : null,
              capabilities));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("failed to encode table metadata", e);
    }
  }

  private static Snapshot snapshot(DeltaKernel.Session session, Long version, Instant timestamp) {
    try {
      if (version != null) {
        return session.table().getSnapshotAsOfVersion(session.engine(), version);
      }
      if (timestamp != null) {
        return session.table().getSnapshotAsOfTimestamp(session.engine(), timestamp.toEpochMilli());
      }
      return session.table().getLatestSnapshot(session.engine());
    } catch (TableNotFoundException missing) {
      throw ApiException.invalidParameter("table '" + session.location() + "' has no Delta log");
    } catch (KernelException | IllegalArgumentException invalid) {
      throw ApiException.invalidParameter(invalid.getMessage());
    }
  }

  private static String ndjson(
      Protocol protocol,
      Metadata metadata,
      ResolvedAsset table,
      Long historicalVersion,
      String capabilities)
      throws JsonProcessingException {
    boolean requiresDelta =
        protocol.getMinReaderVersion() > 1
            || (protocol.getReaderFeatures() != null && !protocol.getReaderFeatures().isEmpty());
    if (DeltaSharingCapabilities.choose(capabilities, requiresDelta)
        == DeltaSharingCapabilities.ResponseFormat.DELTA) {
      return line(new ProtocolLine(new DeltaProtocolWrapper(deltaProtocol(protocol))))
          + line(
              new MetadataLine(
                  new DeltaMetadataBody(
                      historicalVersion,
                      table.storageLocation(),
                      emptyToNull(table.auxiliaryLocations()),
                      accessModes(table),
                      deltaMetadata(metadata))));
    }
    Format format = metadata.getFormat();
    return line(new ProtocolLine(new ParquetProtocolBody(1)))
        + line(
            new MetadataLine(
                new ParquetMetadataBody(
                    metadata.getId(),
                    metadata.getName().orElse(null),
                    metadata.getDescription().orElse(null),
                    table.storageLocation(),
                    emptyToNull(table.auxiliaryLocations()),
                    accessModes(table),
                    new FormatBody(format.getProvider(), emptyMapToNull(format.getOptions())),
                    metadata.getSchemaString(),
                    VectorUtils.toJavaList(metadata.getPartitionColumns()),
                    emptyMapToNull(metadata.getConfiguration()),
                    historicalVersion)));
  }

  private static String line(Object value) throws JsonProcessingException {
    return JSON.writeValueAsString(value) + "\n";
  }

  private static DeltaProtocolBody deltaProtocol(Protocol protocol) {
    return new DeltaProtocolBody(
        protocol.getMinReaderVersion(),
        protocol.getMinWriterVersion(),
        features(protocol.getReaderFeatures()),
        features(protocol.getWriterFeatures()));
  }

  private static DeltaLogMetadata deltaMetadata(Metadata metadata) {
    Format format = metadata.getFormat();
    return new DeltaLogMetadata(
        metadata.getId(),
        metadata.getName().orElse(null),
        metadata.getDescription().orElse(null),
        new FormatBody(format.getProvider(), emptyMapToNull(format.getOptions())),
        metadata.getSchemaString(),
        VectorUtils.toJavaList(metadata.getPartitionColumns()),
        metadata.getCreatedTime().orElse(null),
        emptyMapToNull(metadata.getConfiguration()));
  }

  private static Set<String> features(Set<String> values) {
    return values == null || values.isEmpty() ? null : new TreeSet<>(values);
  }

  private static List<String> accessModes(ResolvedAsset asset) {
    if (asset.format() != TableFormat.DELTA) {
      return null;
    }
    if ("MANAGED".equalsIgnoreCase(asset.subtype())
        || "EXTERNAL".equalsIgnoreCase(asset.subtype())) {
      return List.of("url", "dir");
    }
    return null;
  }

  private static List<String> emptyToNull(List<String> values) {
    return values == null || values.isEmpty() ? null : values;
  }

  private static Map<String, String> emptyMapToNull(Map<String, String> values) {
    return values == null || values.isEmpty() ? null : values;
  }

  public record Result(long version, String ndjson) {}

  private record ProtocolLine(Object protocol) {}

  private record MetadataLine(Object metaData) {}

  private record ParquetProtocolBody(int minReaderVersion) {}

  private record DeltaProtocolWrapper(DeltaProtocolBody deltaProtocol) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record DeltaProtocolBody(
      int minReaderVersion,
      int minWriterVersion,
      Set<String> readerFeatures,
      Set<String> writerFeatures) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record ParquetMetadataBody(
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
      Long version) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record DeltaMetadataBody(
      Long version,
      String location,
      List<String> auxiliaryLocations,
      List<String> accessModes,
      DeltaLogMetadata deltaMetadata) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record DeltaLogMetadata(
      String id,
      String name,
      String description,
      FormatBody format,
      String schemaString,
      List<String> partitionColumns,
      Long createdTime,
      Map<String, String> configuration) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record FormatBody(String provider, Map<String, String> options) {}
}
