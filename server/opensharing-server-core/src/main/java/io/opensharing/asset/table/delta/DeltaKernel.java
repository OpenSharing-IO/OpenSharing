package io.opensharing.asset.table.delta;

import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.defaults.engine.DefaultEngine;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.exceptions.InvalidTableException;
import io.delta.kernel.exceptions.KernelException;
import io.delta.kernel.exceptions.TableNotFoundException;
import io.delta.kernel.internal.TableImpl;
import io.opensharing.asset.table.StorageLocations;
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
   * at or after it. Reads the table as {@code auth}. A timestamp before the first commit resolves
   * to version 0, as the reference server's version endpoint does.
   */
  public long getVersion(ResolvedAsset table, Instant startingTimestamp, AuthContext auth) {
    try (Session session = open(table, auth)) {
      if (startingTimestamp == null) {
        return latestSnapshot(session.table(), session.engine()).getVersion();
      }
      try {
        return ((TableImpl) session.table())
            .getVersionAtOrAfterTimestamp(session.engine(), startingTimestamp.toEpochMilli());
      } catch (KernelException | IllegalArgumentException failed) {
        if (failed.getMessage() != null
            && failed.getMessage().contains("Please use a timestamp greater than or equal")) {
          return 0;
        }
        throw invalidParameter(failed);
      }
    }
  }

  /**
   * The earliest version committed at or after {@code startingTimestamp}, for a Change Data Feed
   * start. A timestamp outside the log is the recipient's bad request, reported with Kernel's
   * message.
   */
  static long versionAtOrAfter(TableImpl table, Engine engine, Instant startingTimestamp) {
    try {
      return table.getVersionAtOrAfterTimestamp(engine, startingTimestamp.toEpochMilli());
    } catch (KernelException | IllegalArgumentException failed) {
      throw invalidParameter(failed);
    }
  }

  /** Kernel's message names the log, but reports the bad parameter, not any storage path. */
  private static ApiException invalidParameter(RuntimeException failed) {
    return ApiException.invalidParameter(failed.getMessage());
  }

  /**
   * The table's latest snapshot. A catalog Delta table whose log is missing or incomplete is the
   * source table's own state, and a protocol Kernel cannot read is the recipient's.
   */
  static Snapshot latestSnapshot(Table table, Engine engine) {
    try {
      return table.getLatestSnapshot(engine);
    } catch (TableNotFoundException missing) {
      log.error("Delta table at '{}' has no log", table.getPath(engine));
      throw ApiException.tableFilesMissing();
    } catch (KernelException failed) {
      if (unsupportedProtocol(failed)) {
        throw unreadableTable(failed);
      }
      if (failed instanceof InvalidTableException invalid) {
        // A log that starts after version 0 is missing commits just like a fully missing one.
        log.error(
            "Delta table at '{}' has an incomplete log: {}",
            table.getPath(engine),
            invalid.getMessage());
        throw ApiException.tableFilesMissing();
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
        "the table requires a Delta reader version or feature this server cannot read."
            + " Contact the data provider.");
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
    HadoopStorageConfiguration.requireSupported(location);
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
