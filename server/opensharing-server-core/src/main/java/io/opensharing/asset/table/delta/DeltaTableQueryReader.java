package io.opensharing.asset.table.delta;

import io.delta.kernel.Scan;
import io.delta.kernel.Snapshot;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.SnapshotImpl;
import io.delta.kernel.internal.actions.AddFile;
import io.delta.kernel.internal.actions.DeletionVectorDescriptor;
import io.delta.kernel.internal.checksum.CRCInfo;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import io.opensharing.asset.table.DeltaSharingCapabilities;
import io.opensharing.asset.table.DeltaSharingCapabilities.ResponseFormat;
import io.opensharing.asset.table.FileIdHash;
import io.opensharing.asset.table.TableActions;
import io.opensharing.asset.table.signer.SignedUrl;
import io.opensharing.asset.table.signer.UrlSigners;
import io.opensharing.auth.AuthContext;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.config.OpenSharingProperties;
import io.opensharing.http.ApiException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Reads Query Table snapshot files from a Delta log. The parquet or delta response is NDJSON:
 * protocol, metaData, then one file action per data file.
 *
 * <p>Every file URL is presigned for {@code opensharing.delta.url-ttl}, so the client reads the
 * data files directly from storage without the provider's credentials.
 */
@Component
public class DeltaTableQueryReader {

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
   * @param version the table version to read; null with a null {@code timestamp} reads the latest
   * @param timestamp read the latest version committed at or before this time
   * @param auth the share owner the catalog vends storage credentials for
   * @param capabilities the request's {@code delta-sharing-capabilities} header; picks the format
   * @param fileIdHash the parsed {@code fileidhash} header, or null to follow the format
   */
  public Result read(
      ResolvedAsset table,
      Long version,
      Instant timestamp,
      AuthContext auth,
      String capabilities,
      String fileIdHash) {
    if (version != null && timestamp != null) {
      throw ApiException.invalidParameter("version and timestamp are mutually exclusive");
    }

    DeltaKernel.Session session = kernel.open(table, auth, properties.getDelta().getUrlTtl());
    Snapshot snapshot = DeltaSnapshots.open(session, version, timestamp);
    SnapshotImpl impl = (SnapshotImpl) snapshot;
    // A time-travel query reports the snapshot version in metaData and on every file.
    boolean historical = version != null || timestamp != null;
    ResponseFormat format = DeltaSharingCapabilities.choose(capabilities);
    // Fails before any file is read when the client cannot read the table in this format.
    DeltaSharingCapabilities.requireReaderFeatures(capabilities, format, impl.getProtocol());
    CRCInfo crc = crcFor(impl, snapshot.getVersion());
    Long fileVersion = historical ? snapshot.getVersion() : null;
    Long fileTimestamp = historical ? snapshot.getTimestamp(session.engine()) : null;
    Duration urlTtl = properties.getDelta().getUrlTtl();

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

    try {
      // Scan files are the add actions that are live in the snapshot after log replay.
      Scan scan = snapshot.getScanBuilder().build();
      try (CloseableIterator<FilteredColumnarBatch> batches = scan.getScanFiles(session.engine())) {
        while (batches.hasNext()) {
          FilteredColumnarBatch batch = batches.next();
          // getRows skips rows the selection vector drops, such as files removed by later commits.
          try (CloseableIterator<Row> rows = batch.getRows()) {
            while (rows.hasNext()) {
              ndjson.append(
                  fileLine(
                      rows.next(),
                      format,
                      fileIdHash,
                      fileVersion,
                      fileTimestamp,
                      session.location(),
                      session.credentials(),
                      urlTtl));
            }
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    return new Result(snapshot.getVersion(), ndjson.toString());
  }

  /**
   * One file action for a scan file. The URL is presigned with the table's storage credentials;
   * {@code expirationTimestamp} is the earliest expiry among the URLs in the line.
   */
  private String fileLine(
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

    AddFile add = new AddFile(scanFile.getStruct(InternalScanFileUtils.ADD_FILE_ORDINAL));
    SignedUrl signed = signers.sign(status.getPath(), credentials, urlTtl);
    String url = signed.url();
    long expirationTimestamp = signed.expiration().toEpochMilli();
    // Hashing the log path, not the URL, keeps the id stable across queries and re-signing.
    String id = FileIdHash.hash(add.getPath(), fileIdHash, format);
    String stats = add.getStatsJson().orElse(null);

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
      return TableActions.deltaFile(
          id,
          deletionVectorFileId,
          expirationTimestamp,
          version,
          timestamp,
          TableActions.withPath(add, url, deletionVectorUrl));
    }
    return TableActions.parquetFile(
        url, id, partitionValues, status.getSize(), stats, version, timestamp, expirationTimestamp);
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
}
