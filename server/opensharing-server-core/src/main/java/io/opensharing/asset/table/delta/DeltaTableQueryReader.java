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
import io.delta.kernel.internal.DeltaLogActionUtils.DeltaAction;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.ScanImpl;
import io.delta.kernel.internal.SnapshotImpl;
import io.delta.kernel.internal.TableImpl;
import io.delta.kernel.internal.actions.AddFile;
import io.delta.kernel.internal.actions.DeletionVectorDescriptor;
import io.delta.kernel.internal.actions.Metadata;
import io.delta.kernel.internal.actions.Protocol;
import io.delta.kernel.internal.actions.RemoveFile;
import io.delta.kernel.internal.checksum.CRCInfo;
import io.delta.kernel.internal.fs.Path;
import io.delta.kernel.internal.util.VectorUtils;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import io.opensharing.asset.table.DeltaSharingCapabilities;
import io.opensharing.asset.table.DeltaSharingCapabilities.ResponseFormat;
import io.opensharing.asset.table.FileIdHash;
import io.opensharing.asset.table.RefreshTokens;
import io.opensharing.asset.table.TableActions;
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
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

  private static final Set<DeltaAction> CHANGE_ACTIONS =
      EnumSet.of(DeltaAction.PROTOCOL, DeltaAction.METADATA, DeltaAction.ADD, DeltaAction.REMOVE);

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
   * too when {@code includeHistoricalProtocol} is set on a delta response.
   */
  public Result readChanges(
      ResolvedAsset table,
      long startingVersion,
      Long endingVersion,
      AuthContext auth,
      String capabilities,
      String fileIdHash,
      boolean includeHistoricalProtocol,
      boolean includeEndStreamAction) {
    DeltaKernel.Session session = kernel.open(table, auth, properties.getDelta().getUrlTtl());
    long latest = DeltaSnapshots.open(session, null, null).getVersion();
    if (startingVersion > latest) {
      throw ApiException.invalidParameter(
          "startingVersion " + startingVersion + " is after the latest table version " + latest);
    }
    if (endingVersion != null && endingVersion > latest) {
      throw ApiException.invalidParameter(
          "endingVersion " + endingVersion + " is after the latest table version " + latest);
    }
    long end = endingVersion == null ? latest : endingVersion;
    SnapshotImpl start = (SnapshotImpl) DeltaSnapshots.open(session, startingVersion, null);
    ResponseFormat format = DeltaSharingCapabilities.choose(capabilities);
    DeltaSharingCapabilities.requireReaderFeatures(capabilities, format, start.getProtocol());
    CRCInfo crc = crcFor(start, startingVersion);
    Duration urlTtl = properties.getDelta().getUrlTtl();
    String tableRoot = session.table().getPath(session.engine());

    StringBuilder ndjson = new StringBuilder();
    ndjson.append(TableActions.protocol(start.getProtocol(), null, format));
    ndjson.append(
        TableActions.metadata(
            start.getMetadata(),
            table,
            startingVersion,
            crc == null ? null : crc.getTableSizeBytes(),
            crc == null ? null : crc.getNumFiles(),
            format));

    Long minUrlExpirationTimestamp = null;
    try (CloseableIterator<ColumnarBatch> batches =
        ((TableImpl) session.table())
            .getChanges(session.engine(), startingVersion, end, CHANGE_ACTIONS)) {
      while (batches.hasNext()) {
        ColumnarBatch batch = batches.next();
        StructType schema = batch.getSchema();
        int versionOrdinal = schema.indexOf("version");
        int timestampOrdinal = schema.indexOf("timestamp");
        int protocolOrdinal = schema.indexOf(DeltaAction.PROTOCOL.colName);
        int metadataOrdinal = schema.indexOf(DeltaAction.METADATA.colName);
        int addOrdinal = schema.indexOf(DeltaAction.ADD.colName);
        int removeOrdinal = schema.indexOf(DeltaAction.REMOVE.colName);
        try (CloseableIterator<Row> rows = batch.getRows()) {
          while (rows.hasNext()) {
            Row row = rows.next();
            long version = row.getLong(versionOrdinal);
            long timestamp = row.getLong(timestampOrdinal);
            if (!row.isNullAt(protocolOrdinal)) {
              Protocol protocol = Protocol.fromRow(row.getStruct(protocolOrdinal));
              DeltaSharingCapabilities.requireReaderFeatures(capabilities, format, protocol);
              if (includeHistoricalProtocol
                  && format == ResponseFormat.DELTA
                  && version > startingVersion) {
                ndjson.append(TableActions.protocol(protocol, version, format));
              }
            } else if (!row.isNullAt(metadataOrdinal)) {
              if (version > startingVersion) {
                ndjson.append(
                    TableActions.metadata(
                        Metadata.fromRow(row.getStruct(metadataOrdinal)),
                        table,
                        version,
                        null,
                        null,
                        format));
              }
            } else if (!row.isNullAt(addOrdinal) || !row.isNullAt(removeOrdinal)) {
              boolean remove = row.isNullAt(addOrdinal);
              FileAction file =
                  changeLine(
                      row.getStruct(remove ? removeOrdinal : addOrdinal),
                      remove,
                      format,
                      fileIdHash,
                      version,
                      timestamp,
                      tableRoot,
                      session,
                      urlTtl);
              if (file == null) {
                continue;
              }
              ndjson.append(file.line());
              minUrlExpirationTimestamp =
                  minUrlExpirationTimestamp == null
                      ? file.expirationTimestamp()
                      : Math.min(minUrlExpirationTimestamp, file.expirationTimestamp());
            }
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (KernelException | IllegalArgumentException invalid) {
      throw ApiException.invalidParameter(invalid.getMessage());
    }

    if (includeEndStreamAction) {
      ndjson.append(TableActions.endStreamAction(null, null, minUrlExpirationTimestamp));
    }
    return new Result(startingVersion, ndjson.toString());
  }

  // Skips files without dataChange, such as compaction rewrites, as the OSS server does.
  private FileAction changeLine(
      Row action,
      boolean remove,
      ResponseFormat format,
      String fileIdHash,
      long version,
      long timestamp,
      String tableRoot,
      DeltaKernel.Session session,
      Duration urlTtl) {
    String path;
    boolean dataChange;
    Map<String, String> partitionValues;
    long size;
    String stats;
    Optional<DeletionVectorDescriptor> deletionVector;
    if (remove) {
      RemoveFile file = new RemoveFile(action);
      path = file.getPath();
      dataChange = file.getDataChange();
      partitionValues =
          file.getPartitionValues()
              .<Map<String, String>>map(VectorUtils::toJavaMap)
              .orElse(Map.of());
      size = file.getSize().orElse(0L);
      stats = null;
      deletionVector = file.getDeletionVector();
    } else {
      AddFile file = new AddFile(action);
      path = file.getPath();
      dataChange = file.getDataChange();
      partitionValues = VectorUtils.toJavaMap(file.getPartitionValues());
      size = file.getSize();
      stats = file.getStatsJson().orElse(null);
      deletionVector = file.getDeletionVector();
    }
    if (!dataChange) {
      return null;
    }
    String absolutePath =
        new Path(new Path(URI.create(tableRoot)), new Path(URI.create(path))).toString();
    SignedUrl signed = signers.sign(absolutePath, session.credentials(), urlTtl);
    long expirationTimestamp = signed.expiration().toEpochMilli();
    String id = FileIdHash.hash(path, fileIdHash, format);

    if (format == ResponseFormat.DELTA) {
      String deletionVectorFileId = null;
      String deletionVectorUrl = null;
      DeletionVectorDescriptor dv =
          deletionVector.filter(DeletionVectorDescriptor::isOnDisk).orElse(null);
      if (dv != null) {
        String dvPath =
            dv.getAbsolutePath(HadoopStorageConfiguration.kernelPath(session.location()));
        SignedUrl signedDv = signers.sign(dvPath, session.credentials(), urlTtl);
        deletionVectorUrl = signedDv.url();
        deletionVectorFileId = FileIdHash.hash(dvPath, fileIdHash, format);
        expirationTimestamp =
            Math.min(expirationTimestamp, signedDv.expiration().toEpochMilli());
      }
      return new FileAction(
          TableActions.deltaChange(
              id,
              deletionVectorFileId,
              expirationTimestamp,
              version,
              timestamp,
              remove,
              TableActions.withPath(action, signed.url(), deletionVectorUrl)),
          expirationTimestamp,
          0);
    }
    return new FileAction(
        TableActions.parquetChange(
            remove,
            signed.url(),
            id,
            partitionValues,
            size,
            stats,
            version,
            timestamp,
            expirationTimestamp),
        expirationTimestamp,
        0);
  }

  /**
   * One file action for a scan file. The URL is presigned with the table's storage credentials;
   * {@code expirationTimestamp} is the earliest expiry among the URLs in the line.
   */
  private FileAction fileLine(
      Row scanFile,
      ResponseFormat format,
      String fileIdHash,
      Long version,
      Long timestamp,
      String tableLocation,
      StorageCredentials credentials,
      Duration urlTtl) {
    FileStatus status = InternalScanFileUtils.getAddFileStatus(scanFile);
    Map<String, String> partitionValues = InternalScanFileUtils.getPartitionValues(scanFile);
    // Parquet file actions always carry partitionValues, empty for an unpartitioned table.
    if (partitionValues == null) {
      partitionValues = Map.of();
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
  }

  /** {@code expirationTimestamp} is the earlier of the file and deletion vector URL expiries. */
  private record SignedFile(
      String url,
      String id,
      String deletionVectorUrl,
      String deletionVectorFileId,
      long expirationTimestamp) {}
}
