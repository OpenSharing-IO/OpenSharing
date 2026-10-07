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
import io.opensharing.asset.table.signer.UrlSigners;
import io.opensharing.auth.AuthContext;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.config.OpenSharingProperties;
import io.opensharing.http.ApiException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Reads Query Table snapshot files from a Delta log. The parquet or delta response is NDJSON:
 * protocol, metaData, then one file action per data file, and an endStreamAction when requested.
 *
 * <p>Every file URL is presigned for {@code opensharing.delta.url-ttl}, so the client reads the
 * data files directly from storage without the provider's credentials.
 *
 * <p>jsonPredicateHints and limitHint only decide which files are returned. Clients still filter
 * rows and apply the limit themselves, so returning extra files is always correct.
 */
@Component
public class DeltaTableQueryReader {

  private static final ObjectMapper JSON = new ObjectMapper();

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
   * @param includeEndStreamAction end the response with an endStreamAction
   * @param jsonPredicateHints the request's hint; an unusable hint returns every file
   * @param limitHint stop once the returned files hold at least this many records; null for all
   */
  public Result read(
      ResolvedAsset table,
      AuthContext auth,
      ResponseOptions options,
      Long version,
      Instant timestamp,
      AuthContext auth,
      String capabilities,
      String fileIdHash,
      boolean historical,
      boolean includeRefreshToken,
      boolean includeEndStreamAction,
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

    StringBuilder ndjson = new StringBuilder();
    ndjson.append(TableActions.protocol(impl.getProtocol(), null, format));
    ndjson.append(
        TableActions.metadata(
            impl.getMetadata(),
            table,
            historical ? snapshot.getVersion() : null,
            crc == null ? null : crc.getTableSizeBytes(),
            crc == null ? null : crc.getNumFiles(),
            format));

    // The earliest URL expiry among the files, reported in the endStreamAction.
    Long minUrlExpirationTimestamp = null;
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
        FilteredColumnarBatch batch = batches.next();
        // getRows skips rows the selection vector drops, such as files removed by later commits.
        try (CloseableIterator<Row> rows = batch.getRows()) {
          while (rows.hasNext()) {
            // Checked before each file, so the last file returned may go past the limit.
            if (limitHint != null && numRecords >= limitHint) {
              break files;
            }
            FileAction file =
                fileLine(
                    rows.next(),
                    format,
                    fileIdHash,
                    fileVersion,
                    fileTimestamp,
                    session.location(),
                    session.credentials(),
                    urlTtl);
            ndjson.append(file.line());
            numRecords += file.numRecords();
            minUrlExpirationTimestamp =
                minUrlExpirationTimestamp == null
                    ? file.expirationTimestamp()
                    : Math.min(minUrlExpirationTimestamp, file.expirationTimestamp());
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

  private DeltaKernel.Session open(ResolvedAsset table, AuthContext auth) {
    return kernel.open(table, auth, urlTtl());
  }

    AddFile add = new AddFile(scanFile.getStruct(InternalScanFileUtils.ADD_FILE_ORDINAL));
    SignedUrl signed = signers.sign(status.getPath(), credentials, urlTtl);
    String url = signed.url();
    long expirationTimestamp = signed.expiration().toEpochMilli();
    // Hashing the log path, not the URL, keeps the id stable across queries and re-signing.
    String id = FileIdHash.hash(add.getPath(), fileIdHash, format);
    String stats = add.getStatsJson().orElse(null);
    // Rows a deletion vector marks deleted do not count toward limitHint.
    long numRecords =
        numRecords(stats)
            - add.getDeletionVector().map(DeletionVectorDescriptor::getCardinality).orElse(0L);

    if (format == ResponseFormat.DELTA) {
      String deletionVectorFileId = null;
      String deletionVectorUrl = null;

      // storageType i = inline bitmap (no object to sign). u/p are on-disk; sign those and set
      // deletionVectorFileId.
      DeletionVectorDescriptor dv =
          add.getDeletionVector().filter(DeletionVectorDescriptor::isOnDisk).orElse(null);
      if (dv != null) {
        String dvPath = dv.getAbsolutePath(HadoopStorageConfiguration.kernelPath(tableLocation));
        SignedUrl signedDv = signers.sign(dvPath, credentials, urlTtl);
        deletionVectorUrl = signedDv.url();
        deletionVectorFileId = FileIdHash.hash(dvPath, fileIdHash, format);
        expirationTimestamp =
            Math.min(expirationTimestamp, signedDv.expiration().toEpochMilli());
      }

      // The delta format nests the add action, with its paths replaced by presigned URLs.
      return new FileAction(
          TableActions.deltaFile(
              id,
              deletionVectorFileId,
              expirationTimestamp,
              version,
              timestamp,
              TableActions.withPath(add, url, deletionVectorUrl)),
          expirationTimestamp,
          numRecords);
    }
    return new FileAction(
        TableActions.parquetFile(
            url,
            id,
            partitionValues,
            status.getSize(),
            stats,
            version,
            timestamp,
            expirationTimestamp),
        expirationTimestamp,
        numRecords);
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

  private record FileAction(String line, long expirationTimestamp, long numRecords) {}
}
