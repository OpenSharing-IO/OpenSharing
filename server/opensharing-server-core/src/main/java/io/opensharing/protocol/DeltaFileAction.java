package io.opensharing.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * One file as delta response format states it: the log's own action, with the path replaced by a url
 * the recipient can read, wrapped with the ids and expiry that belong to sharing rather than to the
 * log.
 *
 * <p>A recipient may write these actions into a local Delta log and let a Delta library read the
 * table from it, which is what lets delta format carry features the parquet format cannot express.
 *
 * @param deletionVectorFileId set when the action carries a deletion vector stored in its own file,
 *     so a client can cache that file as it caches data files
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeltaFileAction(
    String id,
    String deletionVectorFileId,
    Long version,
    Long timestamp,
    Long expirationTimestamp,
    SingleAction deltaSingleAction)
    implements TableAction.File {

  /** Exactly one of the three actions a Delta log records about a data file. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record SingleAction(Add add, Remove remove, Cdc cdc) {

    public static SingleAction of(Add add) {
      return new SingleAction(add, null, null);
    }

    public static SingleAction of(Remove remove) {
      return new SingleAction(null, remove, null);
    }

    public static SingleAction of(Cdc cdc) {
      return new SingleAction(null, null, cdc);
    }
  }

  /**
   * @param path a signed url in place of the log's own path, which is what the recipient reads
   * @param dataChange false when the file only reorganises rows the table already had, which a
   *     streaming reader uses to tell a compaction from an append
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Add(
      String path,
      Map<String, String> partitionValues,
      long size,
      long modificationTime,
      boolean dataChange,
      String stats,
      DeletionVector deletionVector,
      Long baseRowId,
      Long defaultRowCommitVersion) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Remove(
      String path,
      Map<String, String> partitionValues,
      Long size,
      Long deletionTimestamp,
      boolean dataChange,
      DeletionVector deletionVector) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Cdc(
      String path, Map<String, String> partitionValues, long size, boolean dataChange) {}

  /**
   * Which rows of a file have been deleted without rewriting it.
   *
   * @param storageType {@code p} for a vector in its own file, whose {@code pathOrInlineDv} this
   *     server has replaced with a signed url, or {@code i} for one inlined in the action
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record DeletionVector(
      String storageType,
      String pathOrInlineDv,
      Integer offset,
      int sizeInBytes,
      long cardinality) {}
}
