package io.opensharing.asset.delta;

import io.delta.kernel.Scan;
import io.delta.kernel.ScanBuilder;
import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.data.ColumnarBatch;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.defaults.engine.DefaultEngine;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.exceptions.KernelEngineException;
import io.delta.kernel.exceptions.KernelException;
import io.delta.kernel.exceptions.TableNotFoundException;
import io.delta.kernel.internal.DeltaLogActionUtils.DeltaAction;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.ScanImpl;
import io.delta.kernel.internal.SnapshotImpl;
import io.delta.kernel.internal.TableImpl;
import io.delta.kernel.internal.actions.DeletionVectorDescriptor;
import io.delta.kernel.internal.actions.Format;
import io.delta.kernel.internal.util.VectorUtils;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import io.opensharing.asset.storage.HadoopStorage;
import io.opensharing.asset.storage.StoragePaths;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.http.ApiException;
import io.opensharing.http.ErrorCodes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.hadoop.fs.UnsupportedFileSystemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Reads a shared table's Delta log, using credentials the catalog minted for that table's location.
 *
 * <p>What it reads of that storage is the log and nothing else: data files are never opened, only
 * listed and handed to a recipient. Delta Kernel does the log replay — checkpoints, tombstones and
 * protocol versions are its business, not ours — so this class only translates between Kernel's
 * shapes and {@link DeltaSnapshot}, and leaves reaching the storage to {@link HadoopStorage}.
 */
@Service
public class DeltaLogReader {

  private static final Logger log = LoggerFactory.getLogger(DeltaLogReader.class);

  /** Field names as the Delta log writes them, read out of the action structs by name. */
  private static final String STATS_FIELD = "stats";
  private static final String PATH_FIELD = "path";
  private static final String SIZE_FIELD = "size";
  private static final String PARTITION_VALUES_FIELD = "partitionValues";
  private static final String VERSION_FIELD = "version";
  private static final String TIMESTAMP_FIELD = "timestamp";
  private static final String MODIFICATION_TIME_FIELD = "modificationTime";
  private static final String DELETION_TIMESTAMP_FIELD = "deletionTimestamp";
  private static final String DATA_CHANGE_FIELD = "dataChange";
  private static final String DELETION_VECTOR_FIELD = "deletionVector";
  private static final String BASE_ROW_ID_FIELD = "baseRowId";
  private static final String DEFAULT_ROW_COMMIT_VERSION_FIELD = "defaultRowCommitVersion";

  /** The file actions a window can be made of, and which of them each caller wants. */
  private static final Set<DeltaAction> FILE_ACTIONS = Set.of(DeltaAction.ADD, DeltaAction.REMOVE);

  private final HadoopStorage storage;

  public DeltaLogReader(HadoopStorage storage) {
    this.storage = storage;
  }

  /**
   * @param includeFiles whether to list the version's data files, which is the expensive part
   * @throws ApiException if the log cannot be read, phrased for the recipient that asked
   */
  public DeltaSnapshot read(
      String tableRoot, StorageCredentials credentials, DeltaVersion at, boolean includeFiles) {
    Engine engine = engineFor(credentials);
    return reading(
        tableRoot,
        credentials,
        () -> {
          Snapshot snapshot = snapshot(engine, tableRoot, at);
          SnapshotImpl impl = (SnapshotImpl) snapshot;
          return new DeltaSnapshot(
              snapshot.getVersion(),
              snapshot.getTimestamp(engine),
              protocolOf(impl.getProtocol()),
              metadataOf(impl.getMetadata()),
              includeFiles ? files(engine, snapshot, tableRoot) : List.of());
        });
  }

  /**
   * What the log recorded between two versions, inclusive, in commit order.
   *
   * <p>Kernel hands back the log's own actions here rather than a replayed snapshot, which is exactly
   * what a window is: history, not state. The caller says which of them it wants, because the two
   * readers of a window want different things — a change feed wants the {@code cdc} files a table
   * with change data feed writes, while a stream following the table forward wants only the files
   * each commit added and removed.
   *
   * @param includeCdc whether recorded row-level changes count, as against added and removed files
   * @param includeHistory whether schema and protocol changes inside the window are reported too,
   *     which is what lets a reader tell that the table changed shape under it
   */
  public List<DeltaChanges.Entry> changes(
      String tableRoot,
      StorageCredentials credentials,
      long startVersion,
      long endVersion,
      boolean includeCdc,
      boolean includeHistory) {
    if (endVersion < startVersion) {
      throw ApiException.invalidParameter(
          "the ending version " + endVersion + " is before the starting version " + startVersion);
    }
    Engine engine = engineFor(credentials);
    Set<DeltaAction> wanted = wantedActions(includeCdc, includeHistory);
    return reading(
        tableRoot,
        credentials,
        () -> {
          String root = HadoopStorage.path(tableRoot);
          List<DeltaChanges.Entry> entries = new ArrayList<>();
          TableImpl table = (TableImpl) table(engine, tableRoot);
          try (CloseableIterator<ColumnarBatch> batches =
              table.getChanges(engine, startVersion, endVersion, wanted)) {
            while (batches.hasNext()) {
              collectChanges(batches.next(), root, wanted, entries);
            }
          } catch (IOException e) {
            throw new ApiException(
                HttpStatus.BAD_GATEWAY,
                ErrorCodes.INTERNAL_ERROR,
                "failed while reading the table's change files: " + e.getMessage());
          }
          return entries;
        });
  }

  private static Set<DeltaAction> wantedActions(boolean includeCdc, boolean includeHistory) {
    Set<DeltaAction> wanted = new LinkedHashSet<>(FILE_ACTIONS);
    if (includeCdc) {
      wanted.add(DeltaAction.CDC);
    }
    if (includeHistory) {
      wanted.add(DeltaAction.METADATA);
      wanted.add(DeltaAction.PROTOCOL);
    }
    return wanted;
  }

  /**
   * Each row of a change batch carries the commit's version and timestamp and exactly one action, so
   * walking the batches in order walks the window in order.
   */
  private static void collectChanges(
      ColumnarBatch batch,
      String tableRoot,
      Set<DeltaAction> wanted,
      List<DeltaChanges.Entry> entries)
      throws IOException {
    try (CloseableIterator<Row> rows = batch.getRows()) {
      while (rows.hasNext()) {
        Row row = rows.next();
        long version = row.getLong(row.getSchema().indexOf(VERSION_FIELD));
        long timestamp = row.getLong(row.getSchema().indexOf(TIMESTAMP_FIELD));
        for (DeltaAction action : wanted) {
          int ordinal = row.getSchema().indexOf(action.colName);
          if (ordinal < 0 || row.isNullAt(ordinal)) {
            continue;
          }
          entries.add(entry(action, row.getStruct(ordinal), tableRoot, version, timestamp));
        }
      }
    }
  }

  private static DeltaChanges.Entry entry(
      DeltaAction action, Row recorded, String tableRoot, long version, long timestamp) {
    return switch (action) {
      case METADATA ->
          new DeltaChanges.MetadataChange(
              version,
              timestamp,
              metadataOf(io.delta.kernel.internal.actions.Metadata.fromRow(recorded)));
      case PROTOCOL ->
          new DeltaChanges.ProtocolChange(
              version,
              timestamp,
              protocolOf(io.delta.kernel.internal.actions.Protocol.fromRow(recorded)));
      default -> fileChange(kindOf(action), recorded, tableRoot, version, timestamp);
    };
  }

  private static DeltaChanges.FileChange fileChange(
      DeltaChanges.Kind kind, Row action, String tableRoot, long version, long timestamp) {
    return new DeltaChanges.FileChange(
        kind,
        absolute(tableRoot, string(action, PATH_FIELD)),
        longOrZero(action, SIZE_FIELD),
        version,
        timestamp,
        partitionValues(action),
        kind == DeltaChanges.Kind.ADD ? string(action, STATS_FIELD) : null,
        longOrZero(action, MODIFICATION_TIME_FIELD),
        longOrNull(action, DELETION_TIMESTAMP_FIELD),
        booleanOrTrue(action, DATA_CHANGE_FIELD),
        deletionVectorOf(action, tableRoot));
  }

  private static DeltaChanges.Kind kindOf(DeltaAction action) {
    return switch (action) {
      case ADD -> DeltaChanges.Kind.ADD;
      case CDC -> DeltaChanges.Kind.CDF;
      default -> DeltaChanges.Kind.REMOVE;
    };
  }

  private static String absolute(String tableRoot, String path) {
    return path == null ? null : tableRoot + "/" + requireInsideTable(path);
  }

  /**
   * Refuses a path that leaves the table being shared, and decodes the rest.
   *
   * <p>A shared table is a directory, and the grant behind it says nothing about anywhere else, so a
   * log entry naming a file elsewhere — an absolute path, as a shallow clone writes, or one climbing
   * out with {@code ..} — must not be signed. Signing it would hand a recipient bytes the provider
   * never shared, and the server would be the one that fetched them.
   *
   * <p>What is left is relative and URI-escaped, since that is how the log writes a path holding a
   * space or a reserved character, and it has to be decoded before it can be signed.
   */
  static String requireInsideTable(String path) {
    if (path == null || path.isBlank()) {
      throw outsideTheTable(path);
    }
    String decoded = path;
    try {
      decoded = new URI(path).getPath();
    } catch (URISyntaxException e) {
      // A path the log wrote unescaped, which is still a usable path.
    }
    if (decoded.startsWith("/") || path.contains("://") || StoragePaths.climbsOut(decoded)) {
      throw outsideTheTable(path);
    }
    return decoded;
  }

  /** The same rule for a path already resolved against the table root, as a vector's file is. */
  private static String insideTable(String absolutePath, String tableRoot) {
    if (!StoragePaths.isInside(absolutePath, tableRoot)) {
      throw outsideTheTable(absolutePath);
    }
    return absolutePath;
  }

  private static ApiException outsideTheTable(String path) {
    return ApiException.notImplemented(
        "the log of this table records the file '"
            + path
            + "', which lies outside the table's own directory; a table sharing files from "
            + "elsewhere — a shallow clone, for instance — cannot be served in url access mode, "
            + "so use dir access mode and temporary-table-credentials to read it");
  }

  private static Map<String, String> partitionValues(Row action) {
    int ordinal = action.getSchema().indexOf(PARTITION_VALUES_FIELD);
    if (ordinal < 0 || action.isNullAt(ordinal)) {
      return Map.of();
    }
    return VectorUtils.toJavaMap(action.getMap(ordinal));
  }

  private static String string(Row row, String field) {
    int ordinal = row.getSchema().indexOf(field);
    return ordinal < 0 || row.isNullAt(ordinal) ? null : row.getString(ordinal);
  }

  private static long longOrZero(Row row, String field) {
    Long value = longOrNull(row, field);
    return value == null ? 0 : value;
  }

  private static Long longOrNull(Row row, String field) {
    int ordinal = row.getSchema().indexOf(field);
    return ordinal < 0 || row.isNullAt(ordinal) ? null : row.getLong(ordinal);
  }

  /**
   * A flag the log may not have written. Absence means true because that is what the field means for
   * the actions read here: a file the log lists is a file that carries the table's rows unless the
   * writer said otherwise.
   */
  private static boolean booleanOrTrue(Row row, String field) {
    int ordinal = row.getSchema().indexOf(field);
    return ordinal < 0 || row.isNullAt(ordinal) || row.getBoolean(ordinal);
  }

  /**
   * The earliest version at or after a timestamp, which is what the protocol's
   * {@code startingTimestamp} asks for. Kernel answers the opposite question — the latest version at
   * or before — so this walks one commit forward unless that timestamp lands exactly on a commit.
   */
  public long earliestVersionAtOrAfter(
      String tableRoot, StorageCredentials credentials, Instant timestamp) {
    Engine engine = engineFor(credentials);
    return reading(
        tableRoot,
        credentials,
        () -> {
          Table table = table(engine, tableRoot);
          long latest = latestSnapshot(engine, table, tableRoot).getVersion();
          Snapshot atOrBefore;
          try {
            atOrBefore = table.getSnapshotAsOfTimestamp(engine, timestamp.toEpochMilli());
          } catch (KernelException e) {
            // No version at or before the timestamp, so the whole table is after it.
            return 0L;
          }
          if (atOrBefore.getTimestamp(engine) == timestamp.toEpochMilli()) {
            return atOrBefore.getVersion();
          }
          long next = atOrBefore.getVersion() + 1;
          if (next > latest) {
            throw ApiException.invalidParameter(
                "the table has no version at or after " + timestamp + "; the latest is " + latest);
          }
          return next;
        });
  }

  private Snapshot snapshot(Engine engine, String tableRoot, DeltaVersion at) {
    Table table = table(engine, tableRoot);
    if (at.isLatest()) {
      return latestSnapshot(engine, table, tableRoot);
    }
    try {
      return at.version() != null
          ? table.getSnapshotAsOfVersion(engine, at.version())
          : table.getSnapshotAsOfTimestamp(engine, at.timestamp().toEpochMilli());
    } catch (TableNotFoundException e) {
      throw noLog(tableRoot, e);
    } catch (KernelException e) {
      // Asking for a version or time the table never had is the client's mistake.
      throw ApiException.invalidParameter(e.getMessage());
    }
  }

  private Snapshot latestSnapshot(Engine engine, Table table, String tableRoot) {
    try {
      return table.getLatestSnapshot(engine);
    } catch (TableNotFoundException e) {
      throw noLog(tableRoot, e);
    }
  }

  private Table table(Engine engine, String tableRoot) {
    return Table.forPath(engine, HadoopStorage.path(tableRoot));
  }

  private static DeltaSnapshot.Protocol protocolOf(
      io.delta.kernel.internal.actions.Protocol protocol) {
    return new DeltaSnapshot.Protocol(
        protocol.getMinReaderVersion(),
        protocol.getMinWriterVersion(),
        List.copyOf(protocol.getReaderFeatures()),
        List.copyOf(protocol.getWriterFeatures()));
  }

  private static DeltaSnapshot.Metadata metadataOf(
      io.delta.kernel.internal.actions.Metadata metadata) {
    Format format = metadata.getFormat();
    return new DeltaSnapshot.Metadata(
        metadata.getId(),
        metadata.getName().orElse(null),
        metadata.getDescription().orElse(null),
        format.getProvider(),
        format.getOptions(),
        metadata.getSchemaString(),
        partitionColumns(metadata),
        metadata.getConfiguration(),
        metadata.getCreatedTime().orElse(null));
  }

  /**
   * The partition columns as the log lists them, which is the order a reader builds a partition path
   * in. Kernel's other accessor, {@code getPartitionColNames}, lower-cases the names to compare them
   * case-insensitively, so it cannot be used to answer what the columns are called.
   */
  private static List<String> partitionColumns(
      io.delta.kernel.internal.actions.Metadata metadata) {
    return VectorUtils.toJavaList(metadata.getPartitionColumns());
  }

  private List<DeltaSnapshot.File> files(Engine engine, Snapshot snapshot, String tableRoot) {
    ScanBuilder builder = snapshot.getScanBuilder();
    Scan scan = builder.build();
    String root = HadoopStorage.path(tableRoot);
    List<DeltaSnapshot.File> files = new ArrayList<>();
    try (CloseableIterator<FilteredColumnarBatch> batches = scanFiles(engine, scan)) {
      while (batches.hasNext()) {
        try (CloseableIterator<Row> rows = batches.next().getRows()) {
          while (rows.hasNext()) {
            files.add(file(rows.next(), root));
          }
        }
      }
    } catch (IOException e) {
      throw new ApiException(
          HttpStatus.BAD_GATEWAY,
          ErrorCodes.INTERNAL_ERROR,
          "failed while listing the table's files: " + e.getMessage());
    }
    return files;
  }

  /** Statistics are optional in the scan, and the protocol wants them, so ask for them. */
  private static CloseableIterator<FilteredColumnarBatch> scanFiles(Engine engine, Scan scan) {
    return scan instanceof ScanImpl impl ? impl.getScanFiles(engine, true) : scan.getScanFiles(engine);
  }

  /**
   * Kernel has already resolved the path against the table root, so its answer is what gets signed;
   * the log's own entry is checked first, because only that says whether the file was the table's to
   * begin with.
   */
  private static DeltaSnapshot.File file(Row scanFileRow, String tableRoot) {
    Row addFile = scanFileRow.getStruct(InternalScanFileUtils.ADD_FILE_ORDINAL);
    requireInsideTable(string(addFile, PATH_FIELD));
    FileStatus status = InternalScanFileUtils.getAddFileStatus(scanFileRow);
    return new DeltaSnapshot.File(
        status.getPath(),
        status.getSize(),
        status.getModificationTime(),
        booleanOrTrue(addFile, DATA_CHANGE_FIELD),
        InternalScanFileUtils.getPartitionValues(scanFileRow),
        string(addFile, STATS_FIELD),
        deletionVectorOf(addFile, tableRoot),
        longOrNull(addFile, BASE_ROW_ID_FIELD),
        longOrNull(addFile, DEFAULT_ROW_COMMIT_VERSION_FIELD));
  }

  /**
   * The deletion vector an action carries, if it carries one, and where its file is.
   *
   * <p>A vector kept in its own file has to be signed like a data file, so it is resolved against the
   * table root here and refused if it turns out to live somewhere the grant says nothing about. A
   * vector small enough to be inlined in the action has no file and travels as it is.
   */
  private static DeltaSnapshot.DeletionVector deletionVectorOf(Row action, String tableRoot) {
    int ordinal = action.getSchema().indexOf(DELETION_VECTOR_FIELD);
    if (ordinal < 0 || action.isNullAt(ordinal)) {
      return null;
    }
    DeletionVectorDescriptor vector =
        DeletionVectorDescriptor.fromRow(action.getStruct(ordinal));
    String path = vector.isInline() ? null : insideTable(vector.getAbsolutePath(tableRoot), tableRoot);
    return new DeltaSnapshot.DeletionVector(
        vector.getStorageType(),
        vector.getPathOrInlineDv(),
        vector.getOffset().orElse(null),
        vector.getSizeInBytes(),
        vector.getCardinality(),
        path);
  }

  private Engine engineFor(StorageCredentials credentials) {
    return DefaultEngine.create(storage.configurationFor(credentials));
  }

  /**
   * Runs a read and turns a failure to reach storage into an answer the recipient can act on.
   *
   * <p>Three kinds are told apart, because different things fix them: a storage nothing here can
   * address, a driver this deployment left out, and a storage that is simply not answering. The first
   * two are facts about how the server was built and deployed, so they are reported as unimplemented
   * with the mode that still works; the last one may pass, so it is reported as a bad gateway.
   */
  private <T> T reading(String tableRoot, StorageCredentials credentials, Supplier<T> read) {
    try {
      return read.get();
    } catch (KernelEngineException | UncheckedIOException e) {
      // Kernel reports a storage failure either wrapped in its own exception or, where it reads
      // through a stream, as the plain unchecked kind; both mean the log could not be read.
      throw unreadable(tableRoot, e);
    } catch (NoClassDefFoundError e) {
      // A linkage error is not an exception Kernel wraps, but it says the same thing as the wrapped
      // kind: part of a storage driver is missing from this classpath.
      throw missingDriver(tableRoot, e);
    }
  }

  private ApiException unreadable(String tableRoot, RuntimeException failure) {
    if (causedBy(failure, UnsupportedFileSystemException.class)) {
      log.warn("No filesystem addresses {}: {}", tableRoot, failure.getMessage());
      return ApiException.notImplemented(
          "the Delta log under '"
              + tableRoot
              + "' is on a storage this server cannot address; it reads s3a, abfss, gs, hdfs and "
              + "local paths. Use dir access mode and temporary-table-credentials for this table");
    }
    if (causedBy(failure, ClassNotFoundException.class)
        || causedBy(failure, NoClassDefFoundError.class)) {
      return missingDriver(tableRoot, failure);
    }
    log.warn("Could not read the Delta log under {}", tableRoot, failure);
    return new ApiException(
        HttpStatus.BAD_GATEWAY,
        ErrorCodes.INTERNAL_ERROR,
        "the table's storage could not be reached to read its Delta log");
  }

  /**
   * A driver for every storage the protocol names is shipped, so this is only reached by a deployment
   * that slimmed one out — worth saying plainly, because no request will ever make it work.
   */
  private ApiException missingDriver(String tableRoot, Throwable failure) {
    log.warn("A storage driver is missing for {}", tableRoot, failure);
    return ApiException.notImplemented(
        "the driver for the storage under '"
            + tableRoot
            + "' is not on this server's classpath; use dir access mode and "
            + "temporary-table-credentials to read this table");
  }

  private static boolean causedBy(Throwable failure, Class<? extends Throwable> kind) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (kind.isInstance(cause)) {
        return true;
      }
    }
    return false;
  }

  private ApiException noLog(String tableRoot, Exception cause) {
    log.warn("No Delta log under {}: {}", tableRoot, cause.getMessage());
    return ApiException.notFound(
        "no Delta log was found under '" + tableRoot + "', so the table cannot be read");
  }
}
