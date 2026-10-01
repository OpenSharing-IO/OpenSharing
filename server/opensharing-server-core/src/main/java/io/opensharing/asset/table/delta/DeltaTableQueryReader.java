package io.opensharing.asset.table.delta;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.delta.kernel.Scan;
import io.delta.kernel.ScanBuilder;
import io.delta.kernel.Snapshot;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.ScanImpl;
import io.delta.kernel.internal.SnapshotImpl;
import io.delta.kernel.internal.actions.AddFile;
import io.delta.kernel.internal.actions.DeletionVectorDescriptor;
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
 * protocol, metaData, then one file action per data file. jsonPredicateHints and limitHint only
 * skip files; clients still filter rows.
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

  public Result read(
      ResolvedAsset table,
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

    DeltaKernel.Session session = kernel.open(table, auth, properties.getDelta().getUrlTtl());
    Snapshot snapshot = DeltaSnapshots.open(session, version, timestamp);
    SnapshotImpl impl = (SnapshotImpl) snapshot;
    ResponseFormat format = DeltaSharingCapabilities.choose(capabilities);
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

    Long minUrlExpirationTimestamp = null;
    long numRecords = 0;
    ScanBuilder builder = snapshot.getScanBuilder();
    Scan scan =
        JsonPredicateHints.toPredicate(jsonPredicateHints, snapshot.getSchema())
            .map(builder::withFilter)
            .orElse(builder)
            .build();
    // Stats are only read with includeStats; they feed file stats and limitHint record counts.
    try (CloseableIterator<FilteredColumnarBatch> batches =
        ((ScanImpl) scan).getScanFiles(session.engine(), true)) {
      files:
      while (batches.hasNext()) {
        FilteredColumnarBatch batch = batches.next();
        try (CloseableIterator<Row> rows = batch.getRows()) {
          while (rows.hasNext()) {
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

    if (includeRefreshToken || includeEndStreamAction) {
      String refreshToken =
          includeRefreshToken
              ? RefreshTokens.encode(
                  table.identifier(), snapshot.getVersion(), Instant.now().plus(urlTtl))
              : null;
      ndjson.append(
          TableActions.endStreamAction(refreshToken, null, minUrlExpirationTimestamp));
    }

    return new Result(snapshot.getVersion(), ndjson.toString());
  }

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
    if (partitionValues == null) {
      partitionValues = Map.of();
    }

    AddFile add = new AddFile(scanFile.getStruct(InternalScanFileUtils.ADD_FILE_ORDINAL));
    SignedUrl signed = signers.sign(status.getPath(), credentials, urlTtl);
    String url = signed.url();
    long expirationTimestamp = signed.expiration().toEpochMilli();
    String id = FileIdHash.hash(add.getPath(), fileIdHash, format);
    String stats = add.getStatsJson().orElse(null);
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

  private static CRCInfo crcFor(SnapshotImpl snapshot, long version) {
    return snapshot
        .getCurrentCrcInfo()
        .filter(crc -> crc.getVersion() == version)
        .orElse(null);
  }

  public record Result(long version, String ndjson) {}

  private record FileAction(String line, long expirationTimestamp, long numRecords) {}
}
