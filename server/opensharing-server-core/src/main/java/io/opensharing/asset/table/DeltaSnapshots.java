package io.opensharing.asset.table;

import io.delta.kernel.Snapshot;
import io.delta.kernel.exceptions.KernelException;
import io.delta.kernel.exceptions.TableNotFoundException;
import io.opensharing.http.ApiException;
import java.time.Instant;

/** Opens a Kernel snapshot for a version, timestamp, or the latest log. */
final class DeltaSnapshots {

  private DeltaSnapshots() {}

  static Snapshot open(DeltaKernel.Session session, Long version, Instant timestamp) {
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
}
