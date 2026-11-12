package io.opensharing.asset.table.delta;

import io.delta.kernel.Snapshot;
import io.delta.kernel.internal.SnapshotImpl;
import io.opensharing.asset.table.DeltaSharingCapabilities;
import io.opensharing.asset.table.DeltaSharingCapabilities.ResponseFormat;
import io.opensharing.asset.table.TableActions;
import io.opensharing.auth.AuthContext;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.http.ApiException;
import java.time.Instant;

/**
 * Reads Query Table Metadata from a Delta log. The parquet or delta response is two NDJSON lines:
 * protocol, then metaData.
 */
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
    try (DeltaKernel.Session session = kernel.open(table, auth)) {
      Snapshot snapshot = DeltaSnapshots.open(session, version, timestamp);
      SnapshotImpl impl = (SnapshotImpl) snapshot;
      ResponseFormat format =
          DeltaSharingCapabilities.choose(capabilities, impl.getProtocol().getMinReaderVersion());
      return new Result(
          snapshot.getVersion(),
          format,
          TableActions.protocol(impl.getProtocol(), null, format)
              + TableActions.metadata(impl.getMetadata(), table, null, format));
    }
  }

  public record Result(long version, ResponseFormat format, String ndjson) {}
}
