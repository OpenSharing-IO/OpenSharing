package io.opensharing.asset.table.delta;

import io.opensharing.http.ApiException;
import io.opensharing.http.ErrorCodes;

/**
 * The shared source table itself, which its data provider controls, failed a recipient's request.
 * The recipient is told to contact the data provider and is given the table's name as the share
 * names it, which only the protocol layer knows, so it turns this into an {@link ApiException}
 * with {@link #forRecipient(String)}. Server-side detail, such as the table's storage path, stays
 * in the log.
 */
public final class SourceTableException extends RuntimeException {

  private static final String CONTACT_PROVIDER = "Please contact your data provider.";

  private SourceTableException(String logMessage) {
    super(logMessage);
  }

  /** The table's log or data files cannot be found or read where the catalog says they are. */
  static SourceTableException unreadable(String logMessage) {
    return new SourceTableException(logMessage);
  }

  /** The recipient's answer, naming the table {@code sharedName}, as {@code share.schema.table}. */
  public ApiException forRecipient(String sharedName) {
    return ApiException.badRequest(
        "Unable to access table "
            + sharedName
            + ": something is wrong with the table. "
            + CONTACT_PROVIDER);
  }
}
