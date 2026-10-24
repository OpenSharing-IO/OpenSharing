package io.opensharing.asset.table.delta;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.delta.kernel.Scan;
import io.delta.kernel.ScanBuilder;
import io.delta.kernel.Snapshot;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.expressions.Predicate;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.ScanImpl;
import io.delta.kernel.internal.SnapshotImpl;
import io.delta.kernel.internal.actions.AddFile;
import io.delta.kernel.internal.actions.DeletionVectorDescriptor;
import io.delta.kernel.internal.actions.Metadata;
import io.delta.kernel.internal.actions.Protocol;
import io.delta.kernel.internal.checksum.CRCInfo;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import io.opensharing.asset.table.DeltaSharingCapabilities;
import io.opensharing.asset.table.DeltaSharingCapabilities.ResponseFormat;
import io.opensharing.asset.table.FileIdHash;
import io.opensharing.asset.table.RefreshTokens;
import io.opensharing.asset.table.TableActions;
import io.opensharing.asset.table.signer.SignedUrl;
import io.opensharing.asset.table.signer.SigningSession;
import io.opensharing.asset.table.signer.UrlSigners;
import io.opensharing.auth.AuthContext;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.http.ApiException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Reads Query Table snapshot files from a Delta log. The parquet or delta response is NDJSON:
 * protocol, metaData, then one file action per data file, and an endStreamAction when requested.
 *
 * <p>Every file URL is presigned for an hour, or until the catalog's credentials expire if
 * sooner, so the client reads the data files directly from storage without the provider's
 * credentials.
 *
 * <p>jsonPredicateHints and limitHint only decide which files are returned. Clients still filter
 * rows and apply the limit themselves, so returning extra files is always correct.
 */
public class DeltaTableQueryReader {

  private static final Duration URL_TTL = Duration.ofHours(1);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final DeltaKernel kernel;
  private final UrlSigners signers;

  public DeltaTableQueryReader(DeltaKernel kernel, UrlSigners signers) {
    this.kernel = kernel;
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
      version = RefreshTokens.versionOf(refreshToken, table.catalogAssetId());
    }

    try (DeltaKernel.Session session = kernel.open(table, auth, URL_TTL);
        ResponseWriter out = new ResponseWriter(session, table, options)) {
      Snapshot snapshot = DeltaSnapshots.open(session, version, timestamp);
      SnapshotImpl impl = (SnapshotImpl) snapshot;
      out.requireReaderFeatures(impl.getProtocol());
      Long fileVersion = historical ? snapshot.getVersion() : null;
      Long fileTimestamp = historical ? snapshot.getTimestamp(session.engine()) : null;
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
      return new Result(snapshot.getVersion(), out.ndjson());
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
   * Builds one NDJSON response. File URLs are presigned with the table's storage credentials,
   * through one signing session that closing the writer closes, and the earliest expiry among
   * them is reported in the endStreamAction.
   */
  private final class ResponseWriter implements AutoCloseable {

    final ResponseFormat format;
    private final DeltaKernel.Session session;
    private final ResolvedAsset table;
    private final String capabilities;
    private final String fileIdHash;
    private final SigningSession signer;
    private final StringBuilder ndjson = new StringBuilder();
    private Long minUrlExpirationTimestamp;

    ResponseWriter(DeltaKernel.Session session, ResolvedAsset table, ResponseOptions options) {
      this.format = DeltaSharingCapabilities.choose(options.capabilities());
      this.session = session;
      this.table = table;
      this.capabilities = options.capabilities();
      this.fileIdHash = options.fileIdHash();
      this.signer = signers.open(session.credentials());
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
      return Math.max(
          0,
          numRecords(stats)
              - add.getDeletionVector().map(DeletionVectorDescriptor::getCardinality).orElse(0L));
    }

    /** {@code refreshToken} may be null. */
    void endStreamAction(String refreshToken) {
      ndjson.append(TableActions.endStreamAction(refreshToken, minUrlExpirationTimestamp));
    }

    String ndjson() {
      return ndjson.toString();
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
  }

  /** {@code expirationTimestamp} is the earlier of the file and deletion vector URL expiries. */
  private record SignedFile(
      String url,
      String id,
      String deletionVectorUrl,
      String deletionVectorFileId,
      long expirationTimestamp) {}
}
