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
import io.opensharing.asset.table.signer.UrlSigners;
import io.opensharing.auth.AuthContext;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.config.OpenSharingProperties;
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
import org.springframework.stereotype.Component;

/**
 * Reads Query Table files from a Delta log. The parquet or delta response is NDJSON: protocol,
 * metaData, then one file action per data file, or one add or remove per data change file for a
 * startingVersion query, and an endStreamAction when requested.
 *
 * <p>Every file URL is presigned for {@code opensharing.delta.url-ttl}, so the client reads the
 * data files directly from storage without the provider's credentials.
 *
 * <p>jsonPredicateHints and limitHint only decide which snapshot files are returned. Clients still
 * filter rows and apply the limit themselves, so returning extra files is always correct.
 */
@Component
public class DeltaTableQueryReader {

  private static final ObjectMapper JSON = new ObjectMapper();

  // Commit files are read directly because Kernel 4.0's getChanges requires commitInfo fields that
  // older Delta writers omit; only the commitInfo fields used here are read.
  private static final StructType CHANGE_SCHEMA =
      new StructType()
          .add(DeltaAction.PROTOCOL.colName, DeltaAction.PROTOCOL.schema)
          .add(DeltaAction.METADATA.colName, DeltaAction.METADATA.schema)
          .add(DeltaAction.ADD.colName, DeltaAction.ADD.schema)
          .add(DeltaAction.REMOVE.colName, DeltaAction.REMOVE.schema)
          .add(
              DeltaAction.COMMITINFO.colName,
              new StructType().add("inCommitTimestamp", LongType.LONG));

  private final DeltaKernel kernel;
  private final OpenSharingProperties properties;
  private final UrlSigners signers;

  public DeltaTableQueryReader(
      DeltaKernel kernel, OpenSharingProperties properties, UrlSigners signers) {
    this.kernel = kernel;
    this.properties = properties;
    this.signers = signers;
  }

  /**
   * Reads one snapshot of {@code table} as Query Table NDJSON.
   *
   * @param auth the share owner the catalog vends storage credentials for
   * @param version the table version to read; null with a null {@code timestamp} reads the latest
   * @param timestamp read the latest version committed at or before this time
   * @param refreshToken a refresh token from an earlier response; reads the version it pins
   * @param includeRefreshToken put a refresh token for this snapshot in the endStreamAction
   * @param jsonPredicateHints the request's hint; an unusable hint returns every file
   * @param limitHint stop once the returned files hold at least this many records; null for all
   */
  public Result read(
      ResolvedAsset table,
      AuthContext auth,
      ResponseOptions options,
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
      version = RefreshTokens.versionOf(refreshToken, table.identifier());
    }

    DeltaKernel.Session session = open(table, auth);
    Snapshot snapshot = DeltaSnapshots.open(session, version, timestamp);
    SnapshotImpl impl = (SnapshotImpl) snapshot;
    ResponseWriter out = new ResponseWriter(session, table, options);
    // Fails before any file is read when the client cannot read the table in this format.
    out.requireReaderFeatures(impl.getProtocol());
    Long fileVersion = historical ? snapshot.getVersion() : null;
    Long fileTimestamp = historical ? snapshot.getTimestamp(session.engine()) : null;
    out.protocol(impl.getProtocol(), null);
    out.metadata(impl.getMetadata(), fileVersion, crcFor(impl, snapshot.getVersion()));

    // Live records in the files returned so far, compared against limitHint.
    long numRecords = 0;
    // Scan files are the add actions that are live in the snapshot after log replay. With a
    // filter, Kernel also prunes partitions and skips files whose min/max stats cannot match.
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
                  table.identifier(), snapshot.getVersion(), Instant.now().plus(urlTtl()))
              : null);
    }
    return new Result(snapshot.getVersion(), out.ndjson());
  }

  /**
   * Reads data change files committed from {@code startingVersion} through {@code endingVersion},
   * or the latest version when it is null. Protocol and metaData lines come from {@code
   * startingVersion}; later metaData changes follow with their version, and later protocol changes
   * too when {@code includeHistoricalProtocol} is set on a delta response. Versions after the
   * latest are rejected rather than capped.
   */
  public Result readChanges(
      ResolvedAsset table,
      AuthContext auth,
      ResponseOptions options,
      long startingVersion,
      Long endingVersion,
      boolean includeHistoricalProtocol) {
    DeltaKernel.Session session = open(table, auth);
    long latest = DeltaSnapshots.open(session, null, null).getVersion();
    requireAtMostLatest("startingVersion", startingVersion, latest);
    if (endingVersion != null) {
      requireAtMostLatest("endingVersion", endingVersion, latest);
    }
    return changes(
        session,
        table,
        (SnapshotImpl) DeltaSnapshots.open(session, startingVersion, null),
        new ChangeRange(
            startingVersion,
            endingVersion == null ? latest : endingVersion,
            includeHistoricalProtocol),
        options);
  }

  /**
   * Protocol and metaData from {@code header}, then each commit in {@code range} with its add and
   * remove files that have dataChange.
   */
  private Result changes(
      DeltaKernel.Session session,
      ResolvedAsset table,
      SnapshotImpl header,
      ChangeRange range,
      ResponseOptions options) {
    ResponseWriter out = new ResponseWriter(session, table, options);
    out.requireReaderFeatures(header.getProtocol());
    // With includeHistoricalProtocol, the starting protocol carries its version like later ones.
    out.protocol(
        header.getProtocol(),
        range.includeHistoricalProtocol() && out.format == ResponseFormat.DELTA
            ? header.getVersion()
            : null);
    out.metadata(header.getMetadata(), header.getVersion(), crcFor(header, header.getVersion()));

    Path tablePath = new Path(session.table().getPath(session.engine()));
    try {
      for (FileStatus file :
          DeltaLogActionUtils.getCommitFilesForVersionRange(
              session.engine(), tablePath, range.start(), range.end())) {
        write(out, range, readCommit(session, file));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (KernelException | IllegalArgumentException invalid) {
      // Kernel fails when a commit file in the range is missing, such as after log cleanup.
      throw ApiException.invalidParameter(invalid.getMessage());
    }

    if (options.includeEndStreamAction()) {
      out.endStreamAction(null);
    }
    // The protocol reports the starting version as the table version of a change response.
    return new Result(range.start(), out.ndjson());
  }

  /**
   * Reads one commit file, which holds every action of its version. Kernel can split a large file
   * across several batches, each with its own schema ordinals.
   */
  private static Commit readCommit(DeltaKernel.Session session, FileStatus file)
      throws IOException {
    Commit commit = new Commit(FileNames.deltaVersion(file.getPath()), file.getModificationTime());
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
            commit.add(rows.next(), columns);
          }
        }
      }
    }
    return commit;
  }

  private static void write(ResponseWriter out, ChangeRange range, Commit commit) {
    long version = commit.version;
    // The header already covers the starting version's protocol and metaData.
    boolean later = version > range.start();
    // A commit has a protocol only when it creates the table or upgrades the protocol.
    for (Protocol protocol : commit.protocols) {
      // A later protocol can need reader features the client did not declare.
      out.requireReaderFeatures(protocol);
      if (range.includeHistoricalProtocol() && out.format == ResponseFormat.DELTA && later) {
        out.protocol(protocol, version);
      }
    }
    // A commit has metaData only when it creates the table or changes its schema or properties.
    for (Metadata metadata : commit.metadata) {
      if (later) {
        out.metadata(metadata, version, null);
      }
    }
    // A commit has add and remove files only when it writes or rewrites data.
    for (ChangeFile file : commit.files) {
      out.changeFile(file, version, commit.timestamp);
    }
  }

  private static void requireAtMostLatest(String name, long version, long latest) {
    if (version > latest) {
      throw ApiException.invalidParameter(
          name + " " + version + " is after the latest table version " + latest);
    }
  }

  private DeltaKernel.Session open(ResolvedAsset table, AuthContext auth) {
    return kernel.open(table, auth, urlTtl());
  }

  private Duration urlTtl() {
    return properties.getDelta().getUrlTtl();
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

  /** {@code version} is the snapshot read, returned as the {@code Delta-Table-Version} header. */
  public record Result(long version, String ndjson) {}

  /**
   * How one response is written.
   *
   * @param capabilities the request's {@code delta-sharing-capabilities} header; picks the format
   * @param fileIdHash the parsed {@code fileidhash} header, or null to follow the format
   * @param includeEndStreamAction end the response with an endStreamAction
   */
  public record ResponseOptions(
      String capabilities, String fileIdHash, boolean includeEndStreamAction) {}

  /**
   * Builds one NDJSON response. File URLs are presigned with the table's storage credentials, and
   * the earliest expiry among them is reported in the endStreamAction.
   */
  private final class ResponseWriter {

    final ResponseFormat format;
    private final DeltaKernel.Session session;
    private final ResolvedAsset table;
    private final String capabilities;
    private final String fileIdHash;
    private final StringBuilder ndjson = new StringBuilder();
    private Long minUrlExpirationTimestamp;
    private String tableRoot;

    ResponseWriter(DeltaKernel.Session session, ResolvedAsset table, ResponseOptions options) {
      this.format = DeltaSharingCapabilities.choose(options.capabilities());
      this.session = session;
      this.table = table;
      this.capabilities = options.capabilities();
      this.fileIdHash = options.fileIdHash();
    }

    void requireReaderFeatures(Protocol protocol) {
      DeltaSharingCapabilities.requireReaderFeatures(capabilities, format, protocol);
    }

    /** {@code version} is null for the leading protocol and set for a later one. */
    void protocol(Protocol protocol, Long version) {
      ndjson.append(TableActions.protocol(protocol, version, format));
    }

    /** {@code crc} supplies the table size and file count; null omits them. */
    void metadata(Metadata metadata, Long version, CRCInfo crc) {
      ndjson.append(
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
        // The delta format nests the add action, with its paths replaced by presigned URLs.
        ndjson.append(
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
        ndjson.append(
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
      return numRecords(stats)
          - add.getDeletionVector().map(DeletionVectorDescriptor::getCardinality).orElse(0L);
    }

    /** Appends a data change file committed at {@code version}, unless it has no dataChange. */
    void changeFile(ChangeFile file, long version, long timestamp) {
      // Add and remove files without dataChange, such as compaction rewrites, are skipped.
      if (!file.dataChange()) {
        return;
      }
      SignedFile signed = sign(absolutePath(file.path()), file.path(), file.deletionVector());
      if (format == ResponseFormat.DELTA) {
        ndjson.append(
            TableActions.deltaChange(
                signed.id(),
                signed.deletionVectorFileId(),
                signed.expirationTimestamp(),
                version,
                timestamp,
                file.type(),
                TableActions.withPath(file.action(), signed.url(), signed.deletionVectorUrl())));
      } else {
        ndjson.append(
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
      ndjson.append(TableActions.endStreamAction(refreshToken, null, minUrlExpirationTimestamp));
    }

    String ndjson() {
      return ndjson.toString();
    }

    /**
     * Presigns the data file and, for delta responses, its on-disk deletion vector. Inline
     * deletion vectors have no object to sign. Ids hash the log path, not the URL, so they stay
     * stable across queries and re-signing.
     */
    private SignedFile sign(
        String absolutePath, String logPath, Optional<DeletionVectorDescriptor> deletionVector) {
      Duration urlTtl = urlTtl();
      SignedUrl file = signers.sign(absolutePath, session.credentials(), urlTtl);
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
        SignedUrl signedDv = signers.sign(dvPath, session.credentials(), urlTtl);
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
   * The versions and options of a startingVersion query, which also returns later metaData
   * changes.
   *
   * @param includeHistoricalProtocol return protocol changes after {@code start} in delta responses
   */
  private record ChangeRange(long start, long end, boolean includeHistoricalProtocol) {}

  /** Ordinals of a commit file batch. */
  private record ChangeColumns(int protocol, int metadata, int add, int remove, int commitInfo) {

    static ChangeColumns of(StructType schema) {
      return new ChangeColumns(
          schema.indexOf(DeltaAction.PROTOCOL.colName),
          schema.indexOf(DeltaAction.METADATA.colName),
          schema.indexOf(DeltaAction.ADD.colName),
          schema.indexOf(DeltaAction.REMOVE.colName),
          schema.indexOf(DeltaAction.COMMITINFO.colName));
    }
  }

  /** The actions of one commit, buffered so its protocol and metaData go out before its files. */
  private static final class Commit {
    final long version;
    long timestamp;
    final List<Protocol> protocols = new ArrayList<>();
    final List<Metadata> metadata = new ArrayList<>();
    final List<ChangeFile> files = new ArrayList<>();

    /**
     * The commit file's {@code modificationTime} is the timestamp unless commitInfo has an
     * inCommitTimestamp, which tables with in-commit timestamps record because modification times
     * change when the log is copied.
     */
    Commit(long version, long modificationTime) {
      this.version = version;
      this.timestamp = modificationTime;
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
      } else if (!row.isNullAt(columns.commitInfo())) {
        Row commitInfo = row.getStruct(columns.commitInfo());
        int inCommitTimestamp = commitInfo.getSchema().indexOf("inCommitTimestamp");
        if (!commitInfo.isNullAt(inCommitTimestamp)) {
          timestamp = commitInfo.getLong(inCommitTimestamp);
        }
      }
    }
  }

  /** The fields a change line needs from an add or remove action in the log. */
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
      };
    }
  }
}
