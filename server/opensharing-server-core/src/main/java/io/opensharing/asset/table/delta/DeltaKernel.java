package io.opensharing.asset.table.delta;

import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.defaults.engine.DefaultEngine;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.exceptions.KernelException;
import io.delta.kernel.exceptions.TableNotFoundException;
import io.delta.kernel.internal.TableImpl;
import io.opensharing.auth.AuthContext;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.CredentialRequest;
import io.opensharing.catalog.DataSourceFormat;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.catalog.StorageOperation;
import io.opensharing.catalog.TableProperties;
import io.opensharing.exception.CatalogException;
import io.opensharing.http.ApiException;
import io.opensharing.util.StorageLocations;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Opens a Kernel session against catalog-vended credentials for a Delta table root. */
public class DeltaKernel {

  private static final Logger log = LoggerFactory.getLogger(DeltaKernel.class);
  static final Duration CREDENTIAL_TTL = Duration.ofMinutes(5);

  private final CatalogConnector catalog;

  public DeltaKernel(CatalogConnector catalog) {
    this.catalog = catalog;
  }

  /**
   * The table's latest version, or with {@code startingTimestamp} the earliest version committed
   * at or after it. Reads the table as {@code auth}.
   */
  public long getVersion(ResolvedAsset table, Instant startingTimestamp, AuthContext auth) {
    try (Session session = open(table, auth)) {
      return versionAtOrAfter((TableImpl) session.table(), session.engine(), startingTimestamp);
    }
  }

  static long versionAtOrAfter(TableImpl table, Engine engine, Instant startingTimestamp) {
    Snapshot latest = latestSnapshot(table, engine);
    if (startingTimestamp == null) {
      return latest.getVersion();
    }
    if (startingTimestamp.toEpochMilli() > latest.getTimestamp(engine)) {
      throw ApiException.invalidParameter(
          "startingTimestamp is after the latest table version timestamp");
    }
    return table.getVersionAtOrAfterTimestamp(engine, startingTimestamp.toEpochMilli());
  }

  /**
   * The table's latest snapshot. A catalog Delta table without a log is a catalog error, and a
   * protocol Kernel cannot read is the recipient's.
   */
  static Snapshot latestSnapshot(Table table, Engine engine) {
    try {
      return table.getLatestSnapshot(engine);
    } catch (TableNotFoundException missing) {
      log.error("Delta table at '{}' has no log", table.getPath(engine));
      throw new CatalogException("the table has no Delta log");
    } catch (KernelException failed) {
      if (unsupportedProtocol(failed)) {
        throw unreadableTable(failed);
      }
      throw failed;
    }
  }

  /**
   * Whether Kernel cannot read the table's reader version or features, which it reports only by
   * message.
   */
  static boolean unsupportedProtocol(KernelException failed) {
    String message = failed.getMessage();
    return message != null && message.startsWith("Unsupported Delta ");
  }

  /** Kernel's message names the table path, so it stays in the log. */
  static ApiException unreadableTable(KernelException failed) {
    log.warn("Kernel cannot read a shared table: {}", failed.getMessage());
    return ApiException.invalidParameter(
        "the table requires a Delta reader version or feature this server cannot read");
  }

  /** Opens the table's root with read credentials the catalog vends to {@code auth}. */
  public Session open(ResolvedAsset table, AuthContext auth) {
    return open(table, auth, CREDENTIAL_TTL);
  }

  /** Like {@link #open(ResolvedAsset, AuthContext)}, with credentials that last {@code ttl}. */
  public Session open(ResolvedAsset table, AuthContext auth, Duration ttl) {
    if (!(table.additionalProperties() instanceof TableProperties properties)
        || properties.dataSourceFormat() != DataSourceFormat.DELTA) {
      throw ApiException.invalidParameter("the table is not a Delta table");
    }
    String location = table.location() == null ? null : table.location().storageLocation();
    if (location == null || location.isBlank()) {
      log.error("Catalog table '{}' has no storage location", table.fullName());
      throw new CatalogException("the catalog reported no storage location for the table");
    }
    List<StorageCredentials> credentials =
        catalog.getStorageCredentials(
            new CredentialRequest(
                AssetType.TABLE,
                table.fullName(),
                table.catalogAssetId(),
                location,
                StorageOperation.READ,
                ttl),
            auth);
    StorageCredentials rootCredentials =
        credentials.stream()
            .filter(candidate -> StorageLocations.covers(candidate.prefix(), location))
            .findFirst()
            .orElseThrow(
                () -> {
                  log.error("Catalog returned no credentials for table root '{}'", location);
                  return new CatalogException("the catalog returned no credentials for the table");
                });
    String path = HadoopStorageConfiguration.kernelPath(location);
    SessionFileIO files =
        new SessionFileIO(HadoopStorageConfiguration.from(rootCredentials, location), path);
    Engine engine = DefaultEngine.create(files);
    return new Session(engine, Table.forPath(engine, path), location, rootCredentials, files);
  }

  /** A Kernel engine and table reading through files the session owns until it is closed. */
  public record Session(
      Engine engine,
      Table table,
      String location,
      StorageCredentials credentials,
      SessionFileIO files)
      implements AutoCloseable {

    @Override
    public void close() {
      files.close();
    }
  }
}
