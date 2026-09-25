package io.opensharing.asset.table;

import io.delta.kernel.Table;
import io.delta.kernel.defaults.engine.DefaultEngine;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.exceptions.KernelException;
import io.delta.kernel.internal.TableImpl;
import io.opensharing.auth.AuthContext;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.CredentialRequest;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.catalog.StorageOperation;
import io.opensharing.catalog.TableFormat;
import io.opensharing.exception.CatalogException;
import io.opensharing.http.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/** Reads Delta table versions directly from storage with catalog-vended credentials. */
@Component
public class DeltaTableVersionReader {

  private static final Duration CREDENTIAL_TTL = Duration.ofMinutes(5);

  private final CatalogConnector catalog;

  public DeltaTableVersionReader(CatalogConnector catalog) {
    this.catalog = catalog;
  }

  public long getVersion(ResolvedAsset table, Instant startingTimestamp, AuthContext auth) {
    if (table.format() != TableFormat.DELTA) {
      throw ApiException.invalidParameter(
          "table '" + table.identifier() + "' is not a Delta table");
    }
    String location = table.storageLocation();
    if (location == null || location.isBlank()) {
      throw new CatalogException("table '" + table.identifier() + "' has no storage location");
    }
    List<StorageCredentials> credentials =
        catalog.getStorageCredentials(
            new CredentialRequest(
                AssetType.TABLE,
                table.identifier(),
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
    return versionAtOrAfter(
        (TableImpl) Table.forPath(engine, HadoopStorageConfiguration.kernelPath(location)),
        engine,
        startingTimestamp);
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

  private static boolean covers(String prefix, String location) {
    if (prefix == null || prefix.isBlank()) {
      return false;
    }
    String normalized = prefix.endsWith("/") ? prefix : prefix + "/";
    return location.equals(prefix) || location.startsWith(normalized);
  }
}
