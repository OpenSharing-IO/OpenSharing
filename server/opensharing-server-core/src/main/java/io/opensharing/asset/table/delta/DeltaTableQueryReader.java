package io.opensharing.asset.table.delta;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.delta.kernel.Scan;
import io.delta.kernel.ScanBuilder;
import io.delta.kernel.Snapshot;
import io.delta.kernel.data.ColumnarBatch;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.exceptions.KernelException;
import io.delta.kernel.expressions.Predicate;
import io.delta.kernel.internal.DeltaLogActionUtils;
import io.delta.kernel.internal.DeltaLogActionUtils.DeltaAction;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.ScanImpl;
import io.delta.kernel.internal.SnapshotImpl;
import io.delta.kernel.internal.TableConfig;
import io.delta.kernel.internal.TableImpl;
import io.delta.kernel.internal.actions.AddFile;
import io.delta.kernel.internal.actions.DeletionVectorDescriptor;
import io.delta.kernel.internal.actions.Metadata;
import io.delta.kernel.internal.actions.Protocol;
import io.delta.kernel.internal.actions.RemoveFile;
import io.delta.kernel.internal.checksum.CRCInfo;
import io.delta.kernel.internal.fs.Path;
import io.delta.kernel.internal.util.FileNames;
import io.delta.kernel.internal.util.Utils;
import io.delta.kernel.internal.util.VectorUtils;
import io.delta.kernel.types.LongType;
import io.delta.kernel.types.MapType;
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import io.opensharing.asset.table.DeltaSharingCapabilities;
import io.opensharing.asset.table.DeltaSharingCapabilities.ResponseFormat;
import io.opensharing.asset.table.FileIdHash;
import io.opensharing.asset.table.RefreshTokens;
import io.opensharing.asset.table.TableActions;
import io.opensharing.asset.table.TableActions.ChangeType;
import io.opensharing.asset.table.signer.SignedUrl;
import io.opensharing.asset.table.signer.SigningSession;
import io.opensharing.asset.table.signer.UrlSigners;
import io.opensharing.auth.AuthContext;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.http.ApiException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/**
 * Reads Query Table and Change Data Feed files from a Delta log. The parquet or delta response is
 * NDJSON: protocol, metaData, then one file action per data file, or one add, remove, or cdc per
 * data change file for a startingVersion or Change Data Feed query, and an endStreamAction when
 * requested.
 *
 * <p>Every file URL is presigned for an hour, or until the catalog's credentials expire if
 * sooner, so the client reads the data files directly from storage without the provider's
 * credentials.
 *
 * <p>jsonPredicateHints and limitHint only decide which snapshot files are returned. Clients still
 * filter rows and apply the limit themselves, so returning extra files is always correct.
 */
public class DeltaTableQueryReader {

  private static final Duration URL_TTL = Duration.ofHours(1);
  private static final ObjectMapper JSON = new ObjectMapper();

  // Commit files are read directly because Kernel 4.0's getChanges requires commitInfo fields that
  // older Delta writers omit; only the commitInfo fields used here are read.
  private static final StructType CHANGE_SCHEMA =
      new StructType()
          .add(DeltaAction.PROTOCOL.colName, DeltaAction.PROTOCOL.schema)
          .add(DeltaAction.METADATA.colName, DeltaAction.METADATA.schema)
          .add(DeltaAction.ADD.colName, DeltaAction.ADD.schema)
          .add(DeltaAction.REMOVE.colName, DeltaAction.REMOVE.schema)
          .add(DeltaAction.CDC.colName, DeltaAction.CDC.schema)
          .add(
              DeltaAction.COMMITINFO.colName,
              new StructType()
                  .add("inCommitTimestamp", LongType.LONG)
                  .add("operation", StringType.STRING)
                  .add(
                      "operationMetrics",
                      new MapType(StringType.STRING, StringType.STRING, true)));

  private final DeltaKernel kernel;
  private final UrlSigners signers;

  public DeltaTableQueryReader(DeltaKernel kernel, UrlSigners signers) {
    this.kernel = kernel;
    this.signers = signers;
  }

  /**
   * Reads one snapshot of {@code table} as Query Table NDJSON into {@code sink}.
   *
   * @param auth the share owner the catalog vends storage credentials for
   * @param version the table version to read; null with a null {@code timestamp} reads the latest
   * @param timestamp read the latest version committed at or before this time
   * @param refreshToken a refresh token from an earlier response; reads the version it pins
   * @param includeRefreshToken put a refresh token for this snapshot in the endStreamAction
   * @param jsonPredicateHints the request's hint; an unusable hint returns every file
   * @param limitHint stop once the returned files hold at least this many records; null for all
   */
  public void read(
      ResolvedAsset table,
      AuthContext auth,
      ResponseOptions options,
      ResponseSink sink,
      Long version,
      Instant timestamp,
      String refreshToken,
      boolean includeRefreshToken,
      String jsonPredicateHints,
      Long limitHint) {
    if (version != null && timestamp != null) {
      throw ApiException.invalidParameter("version and timestamp are mutually exclusive");
    }
    // A time-travel query reports the snapshot version in metaData and on every file.
    boolean historical = version != null || timestamp != null;
    // A refresh token pins the version it was issued for without making the query historical.
    if (refreshToken != null) {
      version = RefreshTokens.versionOf(refreshToken, table.catalogAssetId());
    }

    try (DeltaKernel.Session session = kernel.open(table, auth, URL_TTL);
        ResponseWriter out = new ResponseWriter(session, table, options, sink)) {
      Snapshot snapshot = DeltaSnapshots.open(session, version, timestamp);
      SnapshotImpl impl = (SnapshotImpl) snapshot;
      out.requireReaderFeatures(impl.getProtocol());
      Long fileVersion = historical ? snapshot.getVersion() : null;
      Long fileTimestamp = historical ? snapshot.getTimestamp(session.engine()) : null;
      out.begin(snapshot.getVersion());
      out.protocol(impl.getProtocol(), null);
      out.metadata(impl.getMetadata(), fileVersion, crcFor(impl, snapshot.getVersion()));

      long numRecords = 0;
      // With a filter, Kernel prunes partitions and skips files whose min/max stats cannot match.
      ScanBuilder builder = snapshot.getScanBuilder();
      Optional<Predicate> filter =
          JsonPredicateHints.toPredicate(jsonPredicateHints, snapshot.getSchema());
      Scan scan = filter.map(builder::withFilter).orElse(builder).build();
      // Stats are only read with includeStats; they feed file stats and limitHint record counts.
      try (CloseableIterator<FilteredColumnarBatch> batches =
          ((ScanImpl) scan).getScanFiles(session.engine(), true)) {
        files:
        while (batches.hasNext()) {
          // getRows skips rows the selection vector drops, such as files removed by later commits.
          try (CloseableIterator<Row> rows = batches.next().getRows()) {
            while (rows.hasNext()) {
              // Checked before each file, so the last file returned may go past the limit.
              if (limitHint != null && numRecords >= limitHint) {
                break files;
              }
              numRecords += out.snapshotFile(rows.next(), fileVersion, fileTimestamp);
            }
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }

      // A refresh token can only travel in an endStreamAction, so asking for one adds the action.
      if (includeRefreshToken || options.includeEndStreamAction()) {
        out.endStreamAction(
            includeRefreshToken
                ? RefreshTokens.encode(
                    table.catalogAssetId(), snapshot.getVersion(), Instant.now().plus(URL_TTL))
                : null);
      }
    }
  }

  /**
   * Reads data change files committed from {@code startingVersion} through {@code endingVersion},
   * or the latest version when it is null. Protocol and metaData lines come from {@code
   * startingVersion}; later metaData changes follow with their version, and later protocol changes
   * too when {@code includeHistoricalProtocol} is set on a delta response. Versions after the
   * latest are rejected rather than capped.
   */
  public void readChanges(
      ResolvedAsset table,
      AuthContext auth,
      ResponseOptions options,
      ResponseSink sink,
      long startingVersion,
      Long endingVersion,
      boolean includeHistoricalProtocol) {
    try (DeltaKernel.Session session = kernel.open(table, auth, URL_TTL)) {
      long latest = DeltaSnapshots.open(session, null, null).getVersion();
      requireAtMostLatest("startingVersion", startingVersion, latest);
      if (endingVersion != null) {
        requireAtMostLatest("endingVersion", endingVersion, latest);
      }
      changes(
          session,
          table,
          (SnapshotImpl) DeltaSnapshots.open(session, startingVersion, null),
          ChangeRange.dataChanges(
              startingVersion,
              endingVersion == null ? latest : endingVersion,
              includeHistoricalProtocol),
          options,
          sink);
    }
  }

  /**
   * Reads the Change Data Feed from the start through the end version or timestamp, or the latest
   * version. Versions take precedence over timestamps, and an end after the latest version is
   * capped, as in the OSS server. Protocol and metaData lines come from the start version with
   * {@code includeHistoricalMetadata}, which also returns later metaData changes, and from the
   * latest version otherwise.
   */
  public void readChangeDataFeed(
      ResolvedAsset table,
      AuthContext auth,
      ResponseOptions options,
      ResponseSink sink,
      Long startingVersion,
      Instant startingTimestamp,
      Long endingVersion,
      Instant endingTimestamp,
      boolean includeHistoricalMetadata,
      boolean includeHistoricalProtocol) {
    try (DeltaKernel.Session session = kernel.open(table, auth, URL_TTL)) {
      SnapshotImpl latest = (SnapshotImpl) DeltaSnapshots.open(session, null, null);
      TableImpl delta = (TableImpl) session.table();
      long start;
      Long end;
      try {
        if (startingVersion != null) {
          start = startingVersion;
        } else if (startingTimestamp != null) {
          start =
              delta.getVersionAtOrAfterTimestamp(
                  session.engine(), startingTimestamp.toEpochMilli());
        } else {
          throw ApiException.invalidParameter("startingVersion or startingTimestamp is required");
        }
        end =
            endingVersion != null
                ? endingVersion
                : endingTimestamp != null
                    ? delta.getVersionBeforeOrAtTimestamp(
                        session.engine(), endingTimestamp.toEpochMilli())
                    : null;
      } catch (KernelException | IllegalArgumentException invalid) {
        throw ApiException.invalidParameter(invalid.getMessage());
      }
      if (start < 0) {
        throw ApiException.invalidParameter("startingVersion must be non-negative");
      }
      requireAtMostLatest("startingVersion", start, latest.getVersion());
      if (end != null && end < start) {
        throw ApiException.invalidParameter(
            "endingVersion " + end + " is before startingVersion " + start);
      }
      long resolvedEnd = end == null ? latest.getVersion() : Math.min(end, latest.getVersion());
      SnapshotImpl first = (SnapshotImpl) DeltaSnapshots.open(session, start, null);
      if (!changeDataFeedEnabled(first.getMetadata())) {
        throw changeDataNotRecorded(start, start, resolvedEnd);
      }
      changes(
          session,
          table,
          includeHistoricalMetadata ? first : latest,
          ChangeRange.changeDataFeed(
              start, resolvedEnd, includeHistoricalMetadata, includeHistoricalProtocol),
          options,
          sink);
    }
  }

  /**
   * Protocol and metaData from {@code header}, then each commit in {@code range}, written as it is
   * read. A Change Data Feed commit with cdc files returns only those, and a no-op MERGE returns no
   * files; otherwise commits return their add and remove files with dataChange.
   */
  private void changes(
      DeltaKernel.Session session,
      ResolvedAsset table,
      SnapshotImpl header,
      ChangeRange range,
      ResponseOptions options,
      ResponseSink sink) {
    try (ResponseWriter out = new ResponseWriter(session, table, options, sink)) {
      out.requireReaderFeatures(header.getProtocol());
      Path tablePath = new Path(session.table().getPath(session.engine()));
      List<FileStatus> commitFiles;
      try {
        commitFiles =
            DeltaLogActionUtils.getCommitFilesForVersionRange(
                session.engine(), tablePath, range.start(), range.end());
      } catch (KernelException | IllegalArgumentException missing) {
        // Kernel fails when a commit file in the range is missing, such as after log cleanup.
        throw ApiException.invalidParameter(
            "the table no longer has every commit from version "
                + range.start()
                + " to "
                + range.end());
      }

      // The protocol reports the starting version as the table version of a change response.
      out.begin(range.start());
      // With includeHistoricalProtocol, the starting protocol carries its version like later ones.
      out.protocol(
          header.getProtocol(),
          range.includeHistoricalProtocol() && out.format == ResponseFormat.DELTA
              ? header.getVersion()
              : null);
      out.metadata(header.getMetadata(), header.getVersion(), crcFor(header, header.getVersion()));
      try {
        for (FileStatus file : commitFiles) {
          if (range.changeDataFeed()) {
            Commit commit = new Commit(file);
            readCommit(session, file, commit::add);
            write(out, range, commit);
          } else {
            readCommit(session, file, new CommitWriter(out, range, file)::write);
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }

      if (options.includeEndStreamAction()) {
        out.endStreamAction(null);
      }
    }
  }

  /**
   * Reads one commit file, which holds every action of its version, in log order. Kernel can split
   * a large file across several batches, each with its own schema ordinals.
   */
  private static void readCommit(
      DeltaKernel.Session session, FileStatus file, BiConsumer<Row, ChangeColumns> action)
      throws IOException {
    try (CloseableIterator<ColumnarBatch> batches =
        session
            .engine()
            .getJsonHandler()
            .readJsonFiles(
                Utils.singletonCloseableIterator(file), CHANGE_SCHEMA, Optional.empty())) {
      while (batches.hasNext()) {
        ColumnarBatch batch = batches.next();
        ChangeColumns columns = ChangeColumns.of(batch.getSchema());
        try (CloseableIterator<Row> rows = batch.getRows()) {
          while (rows.hasNext()) {
            action.accept(rows.next(), columns);
          }
        }
      }
    }
  }

  /** Writes a Change Data Feed commit once it is read whole. */
  private static void write(ResponseWriter out, ChangeRange range, Commit commit) {
    long version = commit.version;
    // The header already covers the starting version's protocol and metaData.
    boolean later = version > range.start();
    for (Protocol protocol : commit.protocols) {
      // A later protocol can need reader features the client did not declare.
      out.requireReaderFeatures(protocol);
      if (range.includeHistoricalProtocol() && out.format == ResponseFormat.DELTA && later) {
        out.protocol(protocol, version);
      }
    }
    for (Metadata metadata : commit.metadata) {
      if (!changeDataFeedEnabled(metadata)) {
        throw changeDataNotRecorded(version, range.start(), range.end());
      }
      if (range.includeHistoricalMetadata() && later) {
        out.metadata(metadata, version, null);
      }
    }
    List<ChangeFile> files = commit.files;
    if (!commit.cdcFiles.isEmpty()) {
      files = commit.cdcFiles;
    } else if (noOpMerge(commit.commitInfo)) {
      files = List.of();
    }
    for (ChangeFile file : files) {
      out.changeFile(file, version, commit.timestamp);
    }
  }

  private static boolean changeDataFeedEnabled(Metadata metadata) {
    return TableConfig.CHANGE_DATA_FEED_ENABLED.fromMetadata(metadata);
  }

  private static ApiException changeDataNotRecorded(long version, long start, long end) {
    return ApiException.invalidParameter(
        "Error getting change data for range ["
            + start
            + ", "
            + end
            + "] as change data was not recorded for version ["
            + version
            + "]");
  }

  // MERGE can rewrite files with dataChange but change no rows; its metrics say so, and no cdc
  // is written. Missing metrics do not skip.
  private static boolean noOpMerge(Row commitInfo) {
    if (commitInfo == null) {
      return false;
    }
    StructType schema = commitInfo.getSchema();
    int operation = schema.indexOf("operation");
    int metrics = schema.indexOf("operationMetrics");
    if (commitInfo.isNullAt(operation)
        || !"MERGE".equals(commitInfo.getString(operation))
        || commitInfo.isNullAt(metrics)) {
      return false;
    }
    Map<String, String> values = VectorUtils.toJavaMap(commitInfo.getMap(metrics));
    return Stream.of("numTargetRowsInserted", "numTargetRowsUpdated", "numTargetRowsDeleted")
        .allMatch(metric -> "0".equals(values.get(metric)));
  }

  private static void requireAtMostLatest(String name, long version, long latest) {
    if (version > latest) {
      throw ApiException.invalidParameter(
          name + " " + version + " is after the latest table version " + latest);
    }
  }

  // Files without stats count as zero records, so limitHint never stops early on them.
  private static long numRecords(String stats) {
    if (stats == null || stats.isBlank()) {
      return 0;
    }
    try {
      return JSON.readTree(stats).path("numRecords").asLong(0);
    } catch (IOException unreadable) {
      return 0;
    }
  }

  /**
   * The snapshot's checksum file, if it was written for exactly {@code version}. Its size and
   * file count go into metaData; without it they are omitted rather than computed by a scan.
   */
  private static CRCInfo crcFor(SnapshotImpl snapshot, long version) {
    return snapshot
        .getCurrentCrcInfo()
        .filter(crc -> crc.getVersion() == version)
        .orElse(null);
  }

  /** Receives a response as it is read. */
  public interface ResponseSink {

    /**
     * Comes once, before the first line. {@code version} is the snapshot read, returned as the
     * {@code Delta-Table-Version} header, and {@code format} is the one the lines are written in.
     */
    void begin(long version, ResponseFormat format);

    /** One NDJSON line, with its trailing newline. */
    void line(String line);
  }

  /**
   * How one response is written.
   *
   * @param capabilities the request's {@code delta-sharing-capabilities} header; with the table's
   *     protocol, picks the format
   * @param fileIdHash the parsed {@code fileidhash} header, or null to follow the format
   * @param includeEndStreamAction end the response with an endStreamAction
   */
  public record ResponseOptions(
      String capabilities, String fileIdHash, boolean includeEndStreamAction) {}

  /**
   * Writes one NDJSON response. File URLs are presigned with the table's storage credentials,
   * through one signing session that closing the writer closes, and the earliest expiry among
   * them is reported in the endStreamAction.
   */
  private final class ResponseWriter implements AutoCloseable {

    ResponseFormat format;
    private final DeltaKernel.Session session;
    private final ResolvedAsset table;
    private final String capabilities;
    private final String fileIdHash;
    private final SigningSession signer;
    private final ResponseSink sink;
    private Long minUrlExpirationTimestamp;
    private String tableRoot;

    ResponseWriter(
        DeltaKernel.Session session,
        ResolvedAsset table,
        ResponseOptions options,
        ResponseSink sink) {
      this.session = session;
      this.table = table;
      this.capabilities = options.capabilities();
      this.fileIdHash = options.fileIdHash();
      this.signer = signers.open(session.credentials());
      this.sink = sink;
    }

    /** Picks the format from the first protocol, before any line is written, then checks each. */
    void requireReaderFeatures(Protocol protocol) {
      if (format == null) {
        format = DeltaSharingCapabilities.choose(capabilities, protocol.getMinReaderVersion());
      }
      DeltaSharingCapabilities.requireReaderFeatures(capabilities, format, protocol);
    }

    /** {@code version} is the response's table version, after the format is picked. */
    void begin(long version) {
      sink.begin(version, format);
    }

    /** {@code version} is null for the leading protocol and set for a later one. */
    void protocol(Protocol protocol, Long version) {
      sink.line(TableActions.protocol(protocol, version, format));
    }

    /** {@code crc} supplies the table size and file count; null omits them. */
    void metadata(Metadata metadata, Long version, CRCInfo crc) {
      sink.line(
          TableActions.metadata(
              metadata,
              table,
              version,
              crc == null ? null : crc.getTableSizeBytes(),
              crc == null ? null : crc.getNumFiles(),
              format));
    }

    /**
     * Appends the file action for a snapshot scan file and returns its live record count, which
     * excludes rows a deletion vector marks deleted.
     */
    long snapshotFile(Row scanFile, Long version, Long timestamp) {
      AddFile add = new AddFile(scanFile.getStruct(InternalScanFileUtils.ADD_FILE_ORDINAL));
      FileStatus status = InternalScanFileUtils.getAddFileStatus(scanFile);
      SignedFile signed = sign(status.getPath(), add.getPath(), add.getDeletionVector());
      String stats = add.getStatsJson().orElse(null);
      if (format == ResponseFormat.DELTA) {
        sink.line(
            TableActions.deltaFile(
                signed.id(),
                signed.deletionVectorFileId(),
                signed.expirationTimestamp(),
                version,
                timestamp,
                TableActions.withPath(add, signed.url(), signed.deletionVectorUrl())));
      } else {
        // Parquet file actions always carry partitionValues, empty for an unpartitioned table.
        Map<String, String> partitionValues = InternalScanFileUtils.getPartitionValues(scanFile);
        sink.line(
            TableActions.parquetFile(
                signed.url(),
                signed.id(),
                partitionValues == null ? Map.of() : partitionValues,
                status.getSize(),
                stats,
                version,
                timestamp,
                signed.expirationTimestamp()));
      }
      return Math.max(
          0,
          numRecords(stats)
              - add.getDeletionVector().map(DeletionVectorDescriptor::getCardinality).orElse(0L));
    }

    /** Appends a data change file committed at {@code version}, unless it has no dataChange. */
    void changeFile(ChangeFile file, long version, long timestamp) {
      if (!file.dataChange()) {
        return;
      }
      SignedFile signed = sign(absolutePath(file.path()), file.path(), file.deletionVector());
      if (format == ResponseFormat.DELTA) {
        sink.line(
            TableActions.deltaChange(
                signed.id(),
                signed.deletionVectorFileId(),
                signed.expirationTimestamp(),
                version,
                timestamp,
                file.type(),
                TableActions.withPath(file.action(), signed.url(), signed.deletionVectorUrl())));
      } else {
        sink.line(
            TableActions.parquetChange(
                file.type(),
                signed.url(),
                signed.id(),
                file.partitionValues(),
                file.size(),
                file.stats(),
                version,
                timestamp,
                signed.expirationTimestamp()));
      }
    }

    /** {@code refreshToken} may be null. */
    void endStreamAction(String refreshToken) {
      sink.line(TableActions.endStreamAction(refreshToken, minUrlExpirationTimestamp));
    }

    @Override
    public void close() {
      signer.close();
    }

    /**
     * Presigns the data file and, for delta responses, its on-disk deletion vector. Inline
     * deletion vectors have no object to sign. Ids hash the log path, not the URL, so they stay
     * stable across queries and re-signing.
     */
    private SignedFile sign(
        String absolutePath, String logPath, Optional<DeletionVectorDescriptor> deletionVector) {
      SignedUrl file = signer.sign(absolutePath, URL_TTL);
      long expirationTimestamp = file.expiration().toEpochMilli();
      String deletionVectorUrl = null;
      String deletionVectorFileId = null;
      DeletionVectorDescriptor dv =
          format == ResponseFormat.DELTA
              ? deletionVector.filter(DeletionVectorDescriptor::isOnDisk).orElse(null)
              : null;
      if (dv != null) {
        String dvPath =
            dv.getAbsolutePath(HadoopStorageConfiguration.kernelPath(session.location()));
        SignedUrl signedDv = signer.sign(dvPath, URL_TTL);
        deletionVectorUrl = signedDv.url();
        deletionVectorFileId = FileIdHash.hash(dvPath, fileIdHash, format);
        expirationTimestamp = Math.min(expirationTimestamp, signedDv.expiration().toEpochMilli());
      }
      minUrlExpirationTimestamp =
          minUrlExpirationTimestamp == null
              ? expirationTimestamp
              : Math.min(minUrlExpirationTimestamp, expirationTimestamp);
      return new SignedFile(
          file.url(),
          FileIdHash.hash(logPath, fileIdHash, format),
          deletionVectorUrl,
          deletionVectorFileId,
          expirationTimestamp);
    }

    // Log paths are URI-encoded and usually relative to the table root; resolving an absolute
    // path against the root keeps it unchanged.
    private String absolutePath(String path) {
      if (tableRoot == null) {
        tableRoot = session.table().getPath(session.engine());
      }
      return new Path(new Path(URI.create(tableRoot)), new Path(URI.create(path))).toString();
    }
  }

  /** {@code expirationTimestamp} is the earlier of the file and deletion vector URL expiries. */
  private record SignedFile(
      String url,
      String id,
      String deletionVectorUrl,
      String deletionVectorFileId,
      long expirationTimestamp) {}

  /**
   * The versions and options of a change query.
   *
   * @param changeDataFeed whether this is a Change Data Feed query, which prefers cdc files
   * @param includeHistoricalMetadata return metaData changes after {@code start}
   * @param includeHistoricalProtocol return protocol changes after {@code start} in delta responses
   */
  private record ChangeRange(
      long start,
      long end,
      boolean changeDataFeed,
      boolean includeHistoricalMetadata,
      boolean includeHistoricalProtocol) {

    // startingVersion queries always return later metaData changes.
    static ChangeRange dataChanges(long start, long end, boolean includeHistoricalProtocol) {
      return new ChangeRange(start, end, false, true, includeHistoricalProtocol);
    }

    static ChangeRange changeDataFeed(
        long start,
        long end,
        boolean includeHistoricalMetadata,
        boolean includeHistoricalProtocol) {
      return new ChangeRange(
          start, end, true, includeHistoricalMetadata, includeHistoricalProtocol);
    }
  }

  /** Ordinals of a commit file batch. */
  private record ChangeColumns(
      int protocol, int metadata, int add, int remove, int cdc, int commitInfo) {

    static ChangeColumns of(StructType schema) {
      return new ChangeColumns(
          schema.indexOf(DeltaAction.PROTOCOL.colName),
          schema.indexOf(DeltaAction.METADATA.colName),
          schema.indexOf(DeltaAction.ADD.colName),
          schema.indexOf(DeltaAction.REMOVE.colName),
          schema.indexOf(DeltaAction.CDC.colName),
          schema.indexOf(DeltaAction.COMMITINFO.colName));
    }
  }

  /** Writes the actions of one data change commit as they are read. */
  private static final class CommitWriter {
    private final ResponseWriter out;
    private final ChangeRange range;
    private final long version;
    // The header already covers the starting version's protocol and metaData.
    private final boolean later;
    private long timestamp;

    /**
     * The commit file's {@code modificationTime} is the timestamp unless commitInfo has an
     * inCommitTimestamp, which tables with in-commit timestamps record because modification times
     * change when the log is copied. Their commitInfo is the commit's first action, so it comes
     * before any file.
     */
    CommitWriter(ResponseWriter out, ChangeRange range, FileStatus file) {
      this.out = out;
      this.range = range;
      this.version = FileNames.deltaVersion(file.getPath());
      this.later = version > range.start();
      this.timestamp = file.getModificationTime();
    }

    // Each line of a commit file holds exactly one action, so one column of the row is set.
    void write(Row row, ChangeColumns columns) {
      if (!row.isNullAt(columns.protocol())) {
        Protocol protocol = Protocol.fromRow(row.getStruct(columns.protocol()));
        // A later protocol can need reader features the client did not declare.
        out.requireReaderFeatures(protocol);
        if (range.includeHistoricalProtocol() && out.format == ResponseFormat.DELTA && later) {
          out.protocol(protocol, version);
        }
      } else if (!row.isNullAt(columns.metadata())) {
        if (later) {
          out.metadata(Metadata.fromRow(row.getStruct(columns.metadata())), version, null);
        }
      } else if (!row.isNullAt(columns.add())) {
        out.changeFile(
            ChangeFile.of(ChangeType.ADD, row.getStruct(columns.add())), version, timestamp);
      } else if (!row.isNullAt(columns.remove())) {
        out.changeFile(
            ChangeFile.of(ChangeType.REMOVE, row.getStruct(columns.remove())), version, timestamp);
      } else if (!row.isNullAt(columns.commitInfo())) {
        Row commitInfo = row.getStruct(columns.commitInfo());
        int inCommitTimestamp = commitInfo.getSchema().indexOf("inCommitTimestamp");
        if (!commitInfo.isNullAt(inCommitTimestamp)) {
          timestamp = commitInfo.getLong(inCommitTimestamp);
        }
      }
    }
  }

  /**
   * The actions of one Change Data Feed commit, buffered because its cdc files take precedence over
   * add and remove, and a no-op MERGE returns no files.
   */
  private static final class Commit {
    final long version;
    long timestamp;
    final List<Protocol> protocols = new ArrayList<>();
    final List<Metadata> metadata = new ArrayList<>();
    final List<ChangeFile> files = new ArrayList<>();
    final List<ChangeFile> cdcFiles = new ArrayList<>();
    Row commitInfo;

    /** The timestamp is chosen as in {@link CommitWriter}. */
    Commit(FileStatus file) {
      this.version = FileNames.deltaVersion(file.getPath());
      this.timestamp = file.getModificationTime();
    }

    // Each line of a commit file holds exactly one action, so one column of the row is set.
    void add(Row row, ChangeColumns columns) {
      if (!row.isNullAt(columns.protocol())) {
        protocols.add(Protocol.fromRow(row.getStruct(columns.protocol())));
      } else if (!row.isNullAt(columns.metadata())) {
        metadata.add(Metadata.fromRow(row.getStruct(columns.metadata())));
      } else if (!row.isNullAt(columns.add())) {
        files.add(ChangeFile.of(ChangeType.ADD, row.getStruct(columns.add())));
      } else if (!row.isNullAt(columns.remove())) {
        files.add(ChangeFile.of(ChangeType.REMOVE, row.getStruct(columns.remove())));
      } else if (!row.isNullAt(columns.cdc())) {
        cdcFiles.add(ChangeFile.of(ChangeType.CDC, row.getStruct(columns.cdc())));
      } else if (!row.isNullAt(columns.commitInfo())) {
        commitInfo = row.getStruct(columns.commitInfo());
        int inCommitTimestamp = commitInfo.getSchema().indexOf("inCommitTimestamp");
        if (!commitInfo.isNullAt(inCommitTimestamp)) {
          timestamp = commitInfo.getLong(inCommitTimestamp);
        }
      }
    }
  }

  /** The fields a change line needs from an add, remove, or cdc action in the log. */
  private record ChangeFile(
      ChangeType type,
      Row action,
      String path,
      boolean dataChange,
      Map<String, String> partitionValues,
      long size,
      String stats,
      Optional<DeletionVectorDescriptor> deletionVector) {

    static ChangeFile of(ChangeType type, Row action) {
      return switch (type) {
        case ADD -> {
          AddFile add = new AddFile(action);
          yield new ChangeFile(
              type,
              action,
              add.getPath(),
              add.getDataChange(),
              VectorUtils.toJavaMap(add.getPartitionValues()),
              add.getSize(),
              add.getStatsJson().orElse(null),
              add.getDeletionVector());
        }
        // A remove may omit partitionValues and size.
        case REMOVE -> {
          RemoveFile remove = new RemoveFile(action);
          yield new ChangeFile(
              type,
              action,
              remove.getPath(),
              remove.getDataChange(),
              remove
                  .getPartitionValues()
                  .<Map<String, String>>map(VectorUtils::toJavaMap)
                  .orElse(Map.of()),
              remove.getSize().orElse(0L),
              null,
              remove.getDeletionVector());
        }
        // cdc files are always data changes and carry no stats or deletion vector.
        case CDC -> {
          StructType schema = action.getSchema();
          yield new ChangeFile(
              type,
              action,
              action.getString(schema.indexOf("path")),
              true,
              VectorUtils.toJavaMap(action.getMap(schema.indexOf("partitionValues"))),
              action.getLong(schema.indexOf("size")),
              null,
              Optional.empty());
        }
      };
    }
  }
}
