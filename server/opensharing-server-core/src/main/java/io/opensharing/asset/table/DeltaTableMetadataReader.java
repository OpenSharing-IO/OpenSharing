package io.opensharing.asset.table;

import io.delta.kernel.Snapshot;
import io.delta.kernel.exceptions.KernelException;
import io.delta.kernel.exceptions.TableNotFoundException;
import io.delta.kernel.internal.SnapshotImpl;
import io.delta.kernel.internal.checksum.CRCInfo;
import io.opensharing.asset.table.DeltaSharingCapabilities.ResponseFormat;
import io.opensharing.auth.AuthContext;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.http.ApiException;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Reads Query Table Metadata from a Delta log. The parquet or delta response is two NDJSON lines:
 * protocol, then metaData.
 */
@Component
public class DeltaTableMetadataReader {

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
    ResponseFormat format = DeltaSharingCapabilities.choose(capabilities);
    CRCInfo crc = crcFor(impl, snapshot.getVersion());
    return new Result(
        snapshot.getVersion(),
        TableActions.protocol(impl.getProtocol(), null, format)
            + TableActions.metadata(
                impl.getMetadata(),
                table,
                historical ? snapshot.getVersion() : null,
                crc == null ? null : crc.getTableSizeBytes(),
                crc == null ? null : crc.getNumFiles(),
                format));
  }

  private static CRCInfo crcFor(SnapshotImpl snapshot, long version) {
    return snapshot
        .getCurrentCrcInfo()
        .filter(crc -> crc.getVersion() == version)
        .orElse(null);
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

  public record Result(long version, String ndjson) {}
}
