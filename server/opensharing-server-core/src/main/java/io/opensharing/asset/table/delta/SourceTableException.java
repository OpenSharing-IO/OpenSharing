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

  private final Long changeDataMissingVersion;

  private SourceTableException(String logMessage, Long changeDataMissingVersion) {
    super(logMessage);
    this.changeDataMissingVersion = changeDataMissingVersion;
  }

  /** The table's log or data files cannot be found or read where the catalog says they are. */
  static SourceTableException unreadable(String logMessage) {
    return new SourceTableException(logMessage, null);
  }

  /** The table did not record its change data for {@code version}, which a change query needs. */
  static SourceTableException changeDataNotRecorded(long version) {
    return new SourceTableException(
        "change data was not recorded for version " + version, version);
  }

  /** The recipient's answer, naming the table {@code sharedName}, as {@code share.schema.table}. */
  public ApiException forRecipient(String sharedName) {
    if (changeDataMissingVersion != null) {
      return ApiException.permissionDenied(
          "Unable to access change data feed for "
              + sharedName
              + ". CDF is not enabled on the original delta table for version "
              + changeDataMissingVersion
              + ". "
              + CONTACT_PROVIDER);
    }
    return ApiException.badRequest(
        "Unable to access table "
            + sharedName
            + ": something is wrong with the table. "
            + CONTACT_PROVIDER);
  }
}
