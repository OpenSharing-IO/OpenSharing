package io.opensharing.asset.table;

import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.defaults.engine.DefaultEngine;
import io.delta.kernel.engine.Engine;
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
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
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
    Engine engine = DefaultEngine.create(configuration(rootCredentials, location));
    return versionAtOrAfter(Table.forPath(engine, kernelPath(location)), engine, startingTimestamp);
  }

  static long versionAtOrAfter(Table table, Engine engine, Instant startingTimestamp) {
    Snapshot latest = table.getLatestSnapshot(engine);
    if (startingTimestamp == null) {
      return latest.getVersion();
    }

    long requestedMillis = startingTimestamp.toEpochMilli();
    Snapshot first = table.getSnapshotAsOfVersion(engine, 0);
    if (requestedMillis <= first.getTimestamp(engine)) {
      return first.getVersion();
    }
    if (requestedMillis > latest.getTimestamp(engine)) {
      throw ApiException.invalidParameter(
          "startingTimestamp is after the latest table version timestamp");
    }

    long low = first.getVersion();
    long high = latest.getVersion();
    while (low < high) {
      long middle = low + (high - low) / 2;
      Snapshot candidate = table.getSnapshotAsOfVersion(engine, middle);
      if (candidate.getTimestamp(engine) < requestedMillis) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }
    return low;
  }

  private static boolean covers(String prefix, String location) {
    if (prefix == null || prefix.isBlank()) {
      return false;
    }
    String normalized = prefix.endsWith("/") ? prefix : prefix + "/";
    return location.equals(prefix) || location.startsWith(normalized);
  }

  private static Configuration configuration(StorageCredentials credentials, String location) {
    Configuration configuration = new Configuration();
    switch (credentials.provider()) {
      case AWS, R2 -> configureS3(configuration, credentials);
      case AZURE -> configureAzure(configuration, credentials, location);
      case GCP -> configureGcs(configuration, credentials);
    }
    return configuration;
  }

  private static void configureS3(
      Configuration configuration, StorageCredentials credentials) {
    configuration.set(
        "fs.s3a.access.key", credentials.require(StorageCredentials.ACCESS_KEY_ID));
    configuration.set(
        "fs.s3a.secret.key", credentials.require(StorageCredentials.SECRET_ACCESS_KEY));
    String token = credentials.credentials().get(StorageCredentials.SESSION_TOKEN);
    if (token != null && !token.isBlank()) {
      configuration.set("fs.s3a.session.token", token);
      configuration.set(
          "fs.s3a.aws.credentials.provider",
          "org.apache.hadoop.fs.s3a.TemporaryAWSCredentialsProvider");
    }
    String region = credentials.credentials().get(StorageCredentials.REGION);
    if (region != null && !region.isBlank()) {
      configuration.set("fs.s3a.endpoint.region", region);
    }
  }

  private static void configureAzure(
      Configuration configuration, StorageCredentials credentials, String location) {
    String host = URI.create(location).getHost();
    if (host == null || host.isBlank()) {
      throw new CatalogException("Azure table location has no storage account host");
    }
    configuration.set("fs.azure.account.auth.type." + host, "SAS");
    configuration.set(
        "fs.azure.sas.token.provider.type." + host,
        "org.apache.hadoop.fs.azurebfs.services.FixedSASTokenProvider");
    configuration.set(
        "fs.azure.sas.fixed.token." + host,
        credentials.require(StorageCredentials.SAS_TOKEN));
  }

  private static void configureGcs(
      Configuration configuration, StorageCredentials credentials) {
    configuration.set("fs.gs.auth.type", "ACCESS_TOKEN_PROVIDER");
    configuration.set(
        "fs.gs.auth.access.token.provider.impl", GcsAccessTokenProvider.class.getName());
    configuration.set(
        GcsAccessTokenProvider.TOKEN,
        credentials.require(StorageCredentials.OAUTH_TOKEN));
    Instant expiration =
        credentials.expiration() != null
            ? credentials.expiration()
            : Instant.now().plus(CREDENTIAL_TTL);
    configuration.set(
        GcsAccessTokenProvider.EXPIRATION,
        Long.toString(expiration.toEpochMilli()));
  }

  private static String kernelPath(String location) {
    return location.startsWith("s3://") ? "s3a://" + location.substring(5) : location;
  }
}
