package io.opensharing.asset.delta;

/**
 * The two shapes a Delta read response can take.
 *
 * <p>{@link #PARQUET} states what a recipient needs to know about each file it may read, and is what
 * every client understands. {@link #DELTA} wraps the log's own actions so a recipient can rebuild a
 * local Delta log and read the table with a Delta library, which is the only way to serve a table
 * whose reader features the parquet shape cannot express.
 */
public enum DeltaResponseFormat {
  PARQUET("parquet"),
  DELTA("delta");

  private final String wireName;

  DeltaResponseFormat(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }
}
