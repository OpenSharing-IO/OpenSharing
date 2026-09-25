package io.opensharing.asset.table;

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

  private static void configureAzure(
      Configuration configuration, StorageCredentials credentials, String location) {
    String host = URI.create(location).getHost();
    if (host == null || host.isBlank()) {
      throw new CatalogException("Azure table location has no storage account host");
    }
    String accountKey = credentials.credentials().get(StorageCredentials.AZURE_ACCOUNT_KEY);
    if (accountKey != null && !accountKey.isBlank()) {
      configuration.set("fs.azure.account.auth.type." + host, "SharedKey");
      configuration.set("fs.azure.account.key." + host, accountKey);
      return;
    }
    configuration.set("fs.azure.account.auth.type." + host, "SAS");
    configuration.set(
        "fs.azure.sas.token.provider.type." + host,
        "org.apache.hadoop.fs.azurebfs.services.FixedSASTokenProvider");
    configuration.set(
        "fs.azure.sas.fixed.token." + host, credentials.require(StorageCredentials.SAS_TOKEN));
  }

  private static void configureGcs(Configuration configuration, StorageCredentials credentials) {
    String keyFile =
        credentials.credentials().get(StorageCredentials.GOOGLE_SERVICE_ACCOUNT_KEY_FILE);
    if (keyFile != null && !keyFile.isBlank()) {
      configuration.set("fs.gs.auth.type", "SERVICE_ACCOUNT_JSON_KEYFILE");
      configuration.set("fs.gs.auth.service.account.json.keyfile", keyFile);
      return;
    }
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
