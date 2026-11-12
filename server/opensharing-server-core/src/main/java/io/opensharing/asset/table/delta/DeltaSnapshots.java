package io.opensharing.asset.table.delta;

import io.delta.kernel.Snapshot;
import io.delta.kernel.exceptions.InvalidTableException;
import io.delta.kernel.exceptions.KernelException;
import io.delta.kernel.internal.SnapshotImpl;
import io.opensharing.http.ApiException;
import java.time.Instant;

/** Opens a Kernel snapshot for a version, timestamp, or the latest log, and reads its checksum. */
final class DeltaSnapshots {

  private DeltaSnapshots() {}

  static Snapshot open(DeltaKernel.Session session, Long version, Instant timestamp) {
    // Kernel's own errors, such as a version after the latest or a timestamp outside the log,
    // are the recipient's bad request; they are reported with Kernel's message.
    if (version != null) {
      try {
        return session.table().getSnapshotAsOfVersion(session.engine(), version);
      } catch (KernelException | IllegalArgumentException unavailable) {
        throw unavailable(unavailable);
      }
    }
    if (timestamp != null) {
      try {
        return session.table().getSnapshotAsOfTimestamp(session.engine(), timestamp.toEpochMilli());
      } catch (KernelException | IllegalArgumentException unavailable) {
        throw unavailable(unavailable);
      }
    }
    return DeltaKernel.latestSnapshot(session.table(), session.engine());
  }

  /** An earlier snapshot can need a protocol Kernel cannot read even when the latest does not. */
  private static ApiException unavailable(RuntimeException failed) {
    if (failed instanceof KernelException kernel && DeltaKernel.unsupportedProtocol(kernel)) {
      return DeltaKernel.unreadableTable(kernel);
    }
    if (failed instanceof InvalidTableException) {
      // The log lost commits, or its beginning, since the latest snapshot was read.
      return ApiException.tableFilesMissing();
    }
    return ApiException.invalidParameter(failed.getMessage());
  }
}
