package io.opensharing.asset.table.delta;

import io.delta.kernel.Scan;
import io.delta.kernel.Snapshot;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.internal.InternalScanFileUtils;
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
import io.opensharing.asset.table.StorageLocations;
import io.opensharing.asset.table.TableActions;
import io.opensharing.asset.table.signer.SignedUrl;
import io.opensharing.asset.table.signer.SigningSession;
import io.opensharing.asset.table.signer.UrlSigners;
import io.opensharing.auth.AuthContext;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.exception.CatalogException;
import io.opensharing.http.ApiException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads Query Table snapshot files from a Delta log. The parquet or delta response is NDJSON:
 * protocol, metaData, then one file action per data file, and an endStreamAction when requested.
 *
 * <p>Every file URL is presigned for an hour, or until the catalog's credentials expire if
 * sooner, so the client reads the data files directly from storage without the provider's
 * credentials.
 */
public class DeltaTableQueryReader {

  private static final Logger log = LoggerFactory.getLogger(DeltaTableQueryReader.class);
  private static final Duration URL_TTL = Duration.ofHours(1);

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
   */
  public void read(
      ResolvedAsset table,
      AuthContext auth,
      ResponseOptions options,
      ResponseSink sink,
      Long version,
      Instant timestamp,
      String refreshToken,
      boolean includeRefreshToken) {
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

      Scan scan = snapshot.getScanBuilder().build();
      try (CloseableIterator<FilteredColumnarBatch> batches = scan.getScanFiles(session.engine())) {
        while (batches.hasNext()) {
          // getRows skips rows the selection vector drops, such as files removed by later commits.
          try (CloseableIterator<Row> rows = batches.next().getRows()) {
            while (rows.hasNext()) {
              out.snapshotFile(rows.next(), fileVersion, fileTimestamp);
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

    /** Appends the file action for a snapshot scan file. */
    void snapshotFile(Row scanFile, Long version, Long timestamp) {
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
      requireUnderRoot(absolutePath);
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
        requireUnderRoot(dvPath);
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

    /**
     * The catalog scoped the credentials to the table root, so a log path outside it, absolute or
     * through {@code ..}, is never signed. Paths compare in Kernel's form, after normalizing.
     */
    private void requireUnderRoot(String path) {
      if (!StorageLocations.covers(kernelForm(session.location()), kernelForm(path))) {
        log.error(
            "Table '{}' references '{}' outside its root '{}'",
            table.fullName(),
            path,
            session.location());
        throw new CatalogException("the table references a file outside its storage location");
      }
    }

    private static String kernelForm(String location) {
      return new Path(HadoopStorageConfiguration.kernelPath(location)).toString();
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
