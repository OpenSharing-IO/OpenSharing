package io.opensharing.asset.table.delta;

import io.delta.kernel.Table;
import io.delta.kernel.defaults.engine.DefaultEngine;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.exceptions.KernelException;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Opens a Kernel session against catalog-vended credentials for a Delta table root. */
public class DeltaKernel {

  private static final Duration CREDENTIAL_TTL = Duration.ofMinutes(5);

  private final CatalogConnector catalog;

  public DeltaKernel(CatalogConnector catalog) {
    this.catalog = catalog;
  }

  /**
   * The table's latest version, or with {@code startingTimestamp} the earliest version committed
   * at or after it. Reads the table as {@code auth}.
   */
  public long getVersion(ResolvedAsset table, Instant startingTimestamp, AuthContext auth) {
    Session session = open(table, auth);
    return versionAtOrAfter((TableImpl) session.table(), session.engine(), startingTimestamp);
  }

  static long versionAtOrAfter(TableImpl table, Engine engine, Instant startingTimestamp) {
    if (startingTimestamp == null) {
      return table.getLatestSnapshot(engine).getVersion();
    }
    try {
      return table.getVersionAtOrAfterTimestamp(engine, startingTimestamp.toEpochMilli());
    } catch (IllegalArgumentException | KernelException invalid) {
      throw ApiException.invalidParameter(
          "startingTimestamp is after the latest table version timestamp");
    }
  }

  private Session open(ResolvedAsset table, AuthContext auth) {
    if (!(table.additionalProperties() instanceof TableProperties properties)
        || properties.dataSourceFormat() != DataSourceFormat.DELTA) {
      throw ApiException.invalidParameter("table '" + table.fullName() + "' is not a Delta table");
    }
    String location = table.location() == null ? null : table.location().storageLocation();
    if (location == null || location.isBlank()) {
      throw new CatalogException("table '" + table.fullName() + "' has no storage location");
    }
    List<StorageCredentials> credentials =
        catalog.getStorageCredentials(
            new CredentialRequest(
                AssetType.TABLE,
                table.fullName(),
                table.catalogAssetId(),
                location,
                StorageOperation.READ,
                CREDENTIAL_TTL),
            auth);
    StorageCredentials rootCredentials =
        credentials.stream()
            .filter(candidate -> covers(candidate.prefix(), location))
            .findFirst()
            .orElseThrow(
                () ->
                    new CatalogException(
                        "catalog returned no credentials for table root '" + location + "'"));
    Engine engine =
        DefaultEngine.create(HadoopStorageConfiguration.from(rootCredentials, location));
    String path = HadoopStorageConfiguration.kernelPath(location);
    return new Session(engine, Table.forPath(engine, path));
  }

  private static boolean covers(String prefix, String location) {
    if (prefix == null || prefix.isBlank()) {
      return false;
    }
    String normalized = prefix.endsWith("/") ? prefix : prefix + "/";
    return location.equals(prefix) || location.startsWith(normalized);
  }

  private record Session(Engine engine, Table table) {}
}
