package io.opensharing.asset.table.delta;

import io.delta.kernel.Snapshot;
import io.delta.kernel.exceptions.KernelException;
import io.delta.kernel.internal.SnapshotImpl;
import io.delta.kernel.internal.checksum.CRCInfo;
import io.opensharing.http.ApiException;
import io.opensharing.http.ErrorCodes;
import java.time.Instant;

/** Opens a Kernel snapshot for a version, timestamp, or the latest log, and reads its checksum. */
final class DeltaSnapshots {

  private DeltaSnapshots() {}

  static Snapshot open(
      DeltaKernel.Session session, Long version, Instant timestamp, long startVersion) {
    Snapshot snapshot = openAt(session, version, timestamp);
    if (snapshot.getVersion() < startVersion) {
      throw ApiException.permissionDenied(
          "You can only query table data since version " + startVersion + ".");
    }
    return snapshot;
  }

  private static Snapshot openAt(DeltaKernel.Session session, Long version, Instant timestamp) {
    Snapshot latest = DeltaKernel.latestSnapshot(session.table(), session.engine());
    if (version == null && timestamp == null) {
      return latest;
    }
    if (version != null && version > latest.getVersion()) {
      // Kernel's message, table path included, without the search back through the log that it
      // makes for a version this far ahead, which takes minutes on object storage.
      throw new ApiException(
          400,
          ErrorCodes.BAD_REQUEST,
          session.table().getPath(session.engine())
              + ": Cannot load table version "
              + version
              + " as it does not exist. The latest available version is "
              + latest.getVersion()
              + ".");
    }
    try {
      return version != null
          ? session.table().getSnapshotAsOfVersion(session.engine(), version)
          : session.table().getSnapshotAsOfTimestamp(session.engine(), timestamp.toEpochMilli());
    } catch (KernelException unavailable) {
      throw DeltaKernel.unsupportedProtocol(unavailable)
          ? DeltaKernel.unreadableTable(unavailable)
          : unavailable;
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
