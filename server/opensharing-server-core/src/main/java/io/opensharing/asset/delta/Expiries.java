package io.opensharing.asset.delta;

import io.opensharing.protocol.EndStreamAction;
import io.opensharing.protocol.TableAction;
import java.util.List;

/**
 * The earliest expiry among the urls a response carries, which is what a client needs in order to
 * know when it must ask again, and what closes a stream the client asked to have closed.
 */
final class Expiries {

  private Long earliest;

  void saw(long expiration) {
    earliest = earliest == null ? expiration : Math.min(earliest, expiration);
  }

  /** Closes the response, if the client said it would be watching for the end. */
  void close(List<TableAction> actions, DeltaSharingCapabilities capabilities) {
    if (capabilities.includeEndStreamAction()) {
      actions.add(TableAction.of(new EndStreamAction(null, null, earliest)));
    }
  }
}
