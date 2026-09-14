package io.opensharing.asset.delta;

import java.util.List;
import java.util.Map;

/**
 * What the Delta log says about a table at one version. This is the only shape the rest of the
 * server sees of a Delta table, so nothing outside {@link DeltaLogReader} depends on Delta Kernel.
 *
 * @param timestamp when the version was committed, in epoch milliseconds, which is what the protocol
 *     reports beside a version a client asked for by name
 * @param files the data files making up the version, empty unless they were asked for, since
 *     listing them is the expensive part of reading a snapshot
 */
public record DeltaSnapshot(
    long version, long timestamp, Protocol protocol, Metadata metadata, List<File> files) {

  public DeltaSnapshot {
    files = files == null ? List.of() : List.copyOf(files);
  }

  /** What a reader must support to read this table, so a client can refuse rather than misread. */
  public record Protocol(
      int minReaderVersion,
      int minWriterVersion,
      List<String> readerFeatures,
      List<String> writerFeatures) {

    public Protocol {
      readerFeatures = readerFeatures == null ? List.of() : List.copyOf(readerFeatures);
      writerFeatures = writerFeatures == null ? List.of() : List.copyOf(writerFeatures);
    }
  }

  /**
   * @param schemaString the schema as the log stores it: a JSON string, passed through untouched
   */
  public record Metadata(
      String id,
      String name,
      String description,
      String formatProvider,
      Map<String, String> formatOptions,
      String schemaString,
      List<String> partitionColumns,
      Map<String, String> configuration,
      Long createdTime) {

    public Metadata {
      formatOptions = formatOptions == null ? Map.of() : Map.copyOf(formatOptions);
      partitionColumns = partitionColumns == null ? List.of() : List.copyOf(partitionColumns);
      configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
    }
  }

  /**
   * One data file of the table.
   *
   * @param path the absolute storage path, which is what gets signed for a recipient
   * @param stats the log's own statistics JSON, passed through untouched, or null if absent
   * @param deletionVector which rows of the file have since been deleted, or null if none have; only
   *     the delta response format can carry this, and a table using them is refused in parquet
   * @param baseRowId row tracking the log may carry, passed through for delta format
   */
  public record File(
      String path,
      long size,
      long modificationTime,
      boolean dataChange,
      Map<String, String> partitionValues,
      String stats,
      DeletionVector deletionVector,
      Long baseRowId,
      Long defaultRowCommitVersion) {

    public File {
      partitionValues = partitionValues == null ? Map.of() : Map.copyOf(partitionValues);
    }
  }

  /**
   * A deletion vector as the log records it, plus where its file actually is.
   *
   * @param absolutePath the vector's own file, which has to be signed like a data file, or null when
   *     the vector is inlined in the action and there is no file
   */
  public record DeletionVector(
      String storageType,
      String pathOrInlineDv,
      Integer offset,
      int sizeInBytes,
      long cardinality,
      String absolutePath) {

    public boolean isInline() {
      return absolutePath == null;
    }
  }
}
