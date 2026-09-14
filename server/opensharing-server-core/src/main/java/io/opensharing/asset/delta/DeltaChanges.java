package io.opensharing.asset.delta;

import java.util.Map;

/**
 * What happened to a table over a range of versions, in commit order.
 *
 * <p>Unlike a snapshot, which says what the table holds, this says what happened to it: files added
 * and removed, and — for a table with change data feed turned on — the recorded before-and-after of
 * an update, which is what a streaming reader consumes.
 *
 * <p>A window is a chronology rather than a list of files, so the entries are ordered and a schema
 * or protocol change inside it appears in its place among them. A reader that asked for those
 * changes can then tell which schema each file was written under.
 *
 * <p>The chronology itself is a plain list, held by whoever asked for it. What lives here is what
 * one entry of it can be, which is the part both response formats have to know.
 */
public final class DeltaChanges {

  private DeltaChanges() {}

  /** Which of the three things a change file records. */
  public enum Kind {
    ADD,
    CDF,
    REMOVE
  }

  /** One thing the log recorded, at the version it recorded it. */
  public sealed interface Entry {
    long version();

    long timestamp();
  }

  /**
   * @param path the absolute storage path, which is what gets signed for a recipient
   * @param timestamp the commit's timestamp, not the file's, since it is the commit a reader tracks
   * @param stats the log's statistics JSON, which only added files carry
   * @param deletionTimestamp when a removal was recorded, which only removals carry
   */
  public record FileChange(
      Kind kind,
      String path,
      long size,
      long version,
      long timestamp,
      Map<String, String> partitionValues,
      String stats,
      long modificationTime,
      Long deletionTimestamp,
      boolean dataChange,
      DeltaSnapshot.DeletionVector deletionVector)
      implements Entry {

    public FileChange {
      partitionValues = partitionValues == null ? Map.of() : Map.copyOf(partitionValues);
    }
  }

  /** A schema change inside the window, reported when the reader asked to see them. */
  public record MetadataChange(long version, long timestamp, DeltaSnapshot.Metadata metadata)
      implements Entry {}

  /** A protocol change inside the window, which can make a table unreadable mid-stream. */
  public record ProtocolChange(long version, long timestamp, DeltaSnapshot.Protocol protocol)
      implements Entry {}
}
