package io.opensharing.asset.table.delta;

import io.delta.kernel.Snapshot;
import io.delta.kernel.internal.SnapshotImpl;
import io.delta.kernel.internal.checksum.CRCInfo;
import io.opensharing.asset.table.DeltaSharingCapabilities;
import io.opensharing.asset.table.DeltaSharingCapabilities.ResponseFormat;
import io.opensharing.asset.table.TableActions;
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
    Snapshot snapshot = DeltaSnapshots.open(session, version, timestamp);
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

  public record Result(long version, String ndjson) {}
}
