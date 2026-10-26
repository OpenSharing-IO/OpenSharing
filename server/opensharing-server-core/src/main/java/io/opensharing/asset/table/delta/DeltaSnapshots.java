package io.opensharing.asset.table.delta;

import io.delta.kernel.Snapshot;
import io.delta.kernel.exceptions.KernelException;
import io.opensharing.http.ApiException;
import java.time.Instant;

/** Opens a Kernel snapshot for a version, timestamp, or the latest log. */
final class DeltaSnapshots {

  private DeltaSnapshots() {}

  static Snapshot open(DeltaKernel.Session session, Long version, Instant timestamp) {
    Snapshot latest = DeltaKernel.latestSnapshot(session.table(), session.engine());
    if (version != null) {
      if (version > latest.getVersion()) {
        throw ApiException.invalidParameter(
            "version " + version + " is after the latest table version " + latest.getVersion());
      }
      try {
        return session.table().getSnapshotAsOfVersion(session.engine(), version);
      } catch (KernelException | IllegalArgumentException unavailable) {
        throw ApiException.invalidParameter("version " + version + " is no longer available");
      }
    }
    if (timestamp != null) {
      if (timestamp.toEpochMilli() > latest.getTimestamp(session.engine())) {
        throw ApiException.invalidParameter(
            "timestamp is after the latest table version timestamp");
      }
      try {
        return session.table().getSnapshotAsOfTimestamp(session.engine(), timestamp.toEpochMilli());
      } catch (KernelException | IllegalArgumentException unavailable) {
        throw ApiException.invalidParameter(
            "timestamp is before the earliest available table version timestamp");
      }
    }
    return latest;
  }
}
