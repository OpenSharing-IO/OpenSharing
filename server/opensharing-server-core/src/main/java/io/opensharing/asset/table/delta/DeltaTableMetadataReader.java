package io.opensharing.asset.table.delta;

import io.delta.kernel.Snapshot;
import io.delta.kernel.exceptions.KernelEngineException;
import io.delta.kernel.exceptions.KernelException;
import io.delta.kernel.internal.SnapshotImpl;
import io.delta.kernel.internal.checksum.CRCInfo;
import io.opensharing.asset.table.DeltaSharingCapabilities;
import io.opensharing.asset.table.DeltaSharingCapabilities.ResponseFormat;
import io.opensharing.asset.table.TableActions;
import io.opensharing.auth.AuthContext;
import io.opensharing.catalog.ResolvedAsset;
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
    try (DeltaKernel.Session session = kernel.open(table, auth)) {
      Snapshot snapshot = DeltaSnapshots.open(session, version, timestamp);
      SnapshotImpl impl = (SnapshotImpl) snapshot;
      DeltaSharingCapabilities.requireSupportedProperties(
          DeltaSharingCapabilities.declared(capabilities),
          impl.getMetadata().getConfiguration(),
          snapshot.getVersion());
      boolean historical = version != null || timestamp != null;
      ResponseFormat format =
          DeltaSharingCapabilities.choose(capabilities, impl.getProtocol().getMinReaderVersion());
      CRCInfo crc = DeltaSnapshots.crcFor(impl);
      return new Result(
          snapshot.getVersion(),
          format,
          TableActions.protocol(impl.getProtocol(), null, format)
              + TableActions.metadata(
                  impl.getMetadata(),
                  table,
                  historical ? snapshot.getVersion() : null,
                  crc == null ? null : crc.getTableSizeBytes(),
                  crc == null ? null : crc.getNumFiles(),
                  format));
    } catch (KernelException | KernelEngineException failed) {
      throw DeltaKernel.classifyRead(failed, table);
    }
  }

  public record Result(long version, ResponseFormat format, String ndjson) {}
}
