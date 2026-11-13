package io.opensharing.asset.table.delta;

import io.delta.kernel.Snapshot;
import io.delta.kernel.exceptions.KernelEngineException;
import io.delta.kernel.exceptions.KernelException;
import io.delta.kernel.internal.SnapshotImpl;
import io.delta.kernel.internal.checksum.CRCInfo;
import java.time.Instant;

/** Opens a Kernel snapshot for a version, timestamp, or the latest log, and reads its checksum. */
final class DeltaSnapshots {

  private DeltaSnapshots() {}

  /**
   * The snapshot at {@code version}, at {@code timestamp}, or the latest. A version or timestamp
   * outside the log is Kernel's report, and is classified like every Kernel failure.
   */
  static Snapshot open(DeltaKernel.Session session, Long version, Instant timestamp) {
    if (version == null && timestamp == null) {
      return session.table().getLatestSnapshot(session.engine());
    }
    try {
      return version != null
          ? session.table().getSnapshotAsOfVersion(session.engine(), version)
          : session.table().getSnapshotAsOfTimestamp(session.engine(), timestamp.toEpochMilli());
    } catch (KernelException | KernelEngineException | IllegalArgumentException unavailable) {
      throw DeltaKernel.classify(
          unavailable, session.table().getPath(session.engine()), version);
    }
  }

  /**
   * The snapshot's checksum file, if it was written for exactly the snapshot's version. Its size
   * and file count go into metaData; without it they are omitted rather than computed by a scan.
   */
  static CRCInfo crcFor(SnapshotImpl snapshot) {
    return snapshot
        .getCurrentCrcInfo()
        .filter(crc -> crc.getVersion() == snapshot.getVersion())
        .orElse(null);
  }
}
