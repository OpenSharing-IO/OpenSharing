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
import org.springframework.stereotype.Component;

/**
 * Reads Query Table Metadata from a Delta log. The parquet response is two NDJSON lines: protocol,
 * then metaData.
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
      ResolvedAsset table, Long version, Instant timestamp, AuthContext auth) {
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
          ndjson(impl.getProtocol(), impl.getMetadata(), table, historical ? snapshot.getVersion() : null));
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
      throw ApiException.invalidParameter(
          "table '" + session.location() + "' has no Delta log");
    } catch (KernelException | IllegalArgumentException invalid) {
      throw ApiException.invalidParameter(invalid.getMessage());
    }
  }

  private static String ndjson(
      Protocol protocol, Metadata metadata, ResolvedAsset table, Long historicalVersion)
      throws JsonProcessingException {
    Format format = metadata.getFormat();
    MetadataBody body =
        new MetadataBody(
            metadata.getId(),
            metadata.getName().orElse(null),
            metadata.getDescription().orElse(null),
            table.storageLocation(),
            emptyToNull(table.auxiliaryLocations()),
            accessModes(table),
            new FormatBody(format.getProvider()),
            metadata.getSchemaString(),
            VectorUtils.toJavaList(metadata.getPartitionColumns()),
            emptyMapToNull(metadata.getConfiguration()),
            historicalVersion);
    return JSON.writeValueAsString(new ProtocolLine(new ProtocolBody(protocol.getMinReaderVersion())))
        + "\n"
        + JSON.writeValueAsString(new MetadataLine(body))
        + "\n";
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

  private record ProtocolLine(ProtocolBody protocol) {}

  private record ProtocolBody(int minReaderVersion) {}

  private record MetadataLine(MetadataBody metaData) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record MetadataBody(
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

  private record FormatBody(String provider) {}
}
