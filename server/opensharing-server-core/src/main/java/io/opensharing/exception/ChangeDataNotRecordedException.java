package io.opensharing.exception;

/**
 * A Change Data Feed read reached a table version that did not record change data. The protocol
 * layer, which knows the table's shared name, answers it as PERMISSION_DENIED.
 */
public class ChangeDataNotRecordedException extends RuntimeException {

  private final long version;

  public ChangeDataNotRecordedException(long version) {
    super("change data was not recorded for version " + version);
    this.version = version;
  }

  /** The first version in the range that did not record change data. */
  public long version() {
    return version;
  }
}
