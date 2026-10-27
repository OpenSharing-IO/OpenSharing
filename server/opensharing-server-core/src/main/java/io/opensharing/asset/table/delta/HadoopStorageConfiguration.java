package io.opensharing.asset.table.delta;

import io.opensharing.catalog.StorageCredentials;
import io.opensharing.exception.CatalogException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.apache.hadoop.conf.Configuration;

/** Builds a Hadoop {@link Configuration} from catalog-vended storage credentials. */
final class HadoopStorageConfiguration {

  private static final Duration CREDENTIAL_TTL = Duration.ofMinutes(5);

  private HadoopStorageConfiguration() {}

  /**
   * Settings to read {@code location} with {@code credentials}. Azure settings are keyed by the
   * location's storage account host.
   */
  static Configuration from(StorageCredentials credentials, String location) {
    Configuration configuration = new Configuration();
    switch (credentials.provider()) {
      case AWS, R2 -> configureS3(configuration, credentials);
      case AZURE -> configureAzure(configuration, credentials, location);
      case GCP -> configureGcs(configuration, credentials);
    }
    return configuration;
  }

  /** Hadoop's S3 connector is s3a://; catalogs typically return s3://. */
  static String kernelPath(String location) {
    return location.startsWith("s3://") ? "s3a://" + location.substring(5) : location;
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
            : Instant.now().plus(CREDENTIAL_TTL);
    configuration.set(
        GcsAccessTokenProvider.EXPIRATION, Long.toString(expiration.toEpochMilli()));
  }
}
