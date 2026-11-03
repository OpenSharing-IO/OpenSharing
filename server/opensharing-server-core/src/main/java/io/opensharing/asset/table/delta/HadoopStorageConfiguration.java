package io.opensharing.asset.table.delta;

import io.opensharing.asset.table.StorageLocations;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.exception.CatalogException;
import io.opensharing.http.ApiException;
import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import org.apache.hadoop.conf.Configuration;

/** Builds a Hadoop {@link Configuration} from catalog-vended storage credentials. */
final class HadoopStorageConfiguration {

  /** File URLs are signed only for abfs:// and abfss://, so WASB locations are not read. */
  private static final Set<String> UNSUPPORTED_SCHEMES = Set.of("wasb", "wasbs");

  private HadoopStorageConfiguration() {}

  /** Rejects a location in storage this build does not read. */
  static void requireSupported(String location) {
    int separator = location.indexOf("://");
    String scheme = separator < 0 ? "" : location.substring(0, separator).toLowerCase(Locale.ROOT);
    if (UNSUPPORTED_SCHEMES.contains(scheme)) {
      throw ApiException.notImplemented(
          "this build cannot read tables in '" + scheme + "' storage");
    }
  }

  /**
   * Settings to read {@code location} with {@code credentials}. Azure settings are keyed by the
   * location's storage account host.
   */
  static Configuration from(StorageCredentials credentials, String location) {
    Configuration configuration = new Configuration();
    switch (credentials.provider()) {
      case AWS -> configureS3(configuration, credentials);
      case R2 -> configureR2(configuration, credentials, location);
      case AZURE -> configureAzure(configuration, credentials, location);
      case GCP -> configureGcs(configuration, credentials);
    }
    return configuration;
  }

  /**
   * Hadoop's S3 connector is s3a://, which catalogs typically return as s3://, and it reads R2
   * buckets too.
   */
  static String kernelPath(String location) {
    if (location.startsWith("s3://")) {
      return "s3a://" + location.substring(5);
    }
    if (location.startsWith("r2://")) {
      StorageLocations.R2Object r2 = StorageLocations.r2Object(location);
      return "s3a://" + r2.bucket() + "/" + r2.key();
    }
    return location;
  }

  /**
   * Temporary credentials when the catalog vends a session token, otherwise the key pair alone.
   * Without a region, S3A falls back to the AWS SDK's default region resolution.
   */
  private static void configureS3(Configuration configuration, StorageCredentials credentials) {
    configuration.set("fs.s3a.access.key", credentials.require(StorageCredentials.ACCESS_KEY_ID));
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

  /**
   * S3 settings at the R2 account's endpoint, the host its r2:// location names. S3A reserves the
   * region name auto, so R2's alias for it, us-east-1, is set instead.
   */
  private static void configureR2(
      Configuration configuration, StorageCredentials credentials, String location) {
    if (!location.startsWith("r2://")) {
      throw new CatalogException(
          "the catalog returned R2 credentials for a table location that is not r2://");
    }
    configureS3(configuration, credentials);
    configuration.set("fs.s3a.endpoint", "https://" + StorageLocations.r2Object(location).host());
    configuration.set("fs.s3a.endpoint.region", "us-east-1");
    configuration.set("fs.s3a.path.style.access", "true");
  }

  /** The catalog's SAS token for every request to the storage account. */
  private static void configureAzure(
      Configuration configuration, StorageCredentials credentials, String location) {
    String host = URI.create(location).getHost();
    if (host == null || host.isBlank()) {
      throw new CatalogException("Azure table location has no storage account host");
    }
    configuration.set("fs.azure.account.auth.type." + host, "SAS");
    configuration.set(
        "fs.azure.sas.fixed.token." + host, credentials.require(StorageCredentials.SAS_TOKEN));
  }

  /**
   * The catalog's OAuth token, served by {@link GcsAccessTokenProvider} until the credentials
   * expire, or for five minutes when the catalog sets no expiration.
   */
  private static void configureGcs(Configuration configuration, StorageCredentials credentials) {
    configuration.set("fs.gs.auth.type", "ACCESS_TOKEN_PROVIDER");
    configuration.set(
        "fs.gs.auth.access.token.provider.impl", GcsAccessTokenProvider.class.getName());
    configuration.set(
        GcsAccessTokenProvider.TOKEN, credentials.require(StorageCredentials.OAUTH_TOKEN));
    Instant expiration =
        credentials.expiration() != null
            ? credentials.expiration()
            : Instant.now().plus(DeltaKernel.CREDENTIAL_TTL);
    configuration.set(
        GcsAccessTokenProvider.EXPIRATION, Long.toString(expiration.toEpochMilli()));
  }
}
