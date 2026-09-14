package io.opensharing.asset.storage;

import com.google.cloud.hadoop.util.AccessTokenProvider;
import io.opensharing.catalog.StorageCredentialKeys;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.config.OpenSharingProperties;
import io.opensharing.http.ApiException;
import java.util.List;
import java.util.Locale;
import org.apache.hadoop.conf.Configuration;
import org.springframework.stereotype.Component;

/**
 * How a read that goes through Hadoop reaches the storage a table lives in.
 *
 * <p>Only one kind of read does: replaying a Delta log, which Delta Kernel's default engine does
 * through Hadoop's filesystems. Everything else the server reads it fetches from a url it signed
 * itself, so this is the whole of the server's Hadoop surface — the credentials a driver takes,
 * and the spelling it wants a path in.
 */
@Component
public class HadoopStorage {

  /** Region S3 reads are made against when the catalog's credentials do not name one. */
  private final String defaultS3Region;

  public HadoopStorage(OpenSharingProperties properties) {
    this.defaultS3Region = properties.getStorage().getS3Region();
  }

  /**
   * Hands the catalog's credentials to Hadoop. Each provider takes them its own way — S3 as a
   * session triple, Azure as the SAS itself, Google only through a provider class it instantiates —
   * but the values are the same ones a recipient gets from {@code temporary-table-credentials}: this
   * server reads with exactly the access it hands out, never more, and a fresh configuration per
   * read keeps one table's credentials out of another's reach.
   *
   * <p>When the catalog vends nothing, the deployment's own Hadoop configuration decides, which is
   * how the reference sharing server reaches every table. The same fallback carries the two cases
   * this cannot serve: Azure's older {@code wasb} filesystem, whose SAS support mints tokens from an
   * account key rather than accepting one, and any storage whose access is granted by the machine
   * the server runs on rather than by the catalog.
   */
  public Configuration configurationFor(StorageCredentials credentials) {
    Configuration conf = new Configuration();
    if (credentials == null) {
      return conf;
    }
    switch (credentials.provider()) {
      case AWS, R2 -> {
        conf.set(
            "fs.s3a.aws.credentials.provider",
            "org.apache.hadoop.fs.s3a.TemporaryAWSCredentialsProvider");
        conf.set("fs.s3a.access.key", credentials.require(StorageCredentialKeys.ACCESS_KEY_ID));
        conf.set("fs.s3a.secret.key", credentials.require(StorageCredentialKeys.SECRET_ACCESS_KEY));
        conf.set("fs.s3a.session.token", credentials.require(StorageCredentialKeys.SESSION_TOKEN));
        // Named rather than discovered, so a read never waits on the machine's own instance
        // metadata for a region the catalog already reported — the one its urls are signed for.
        conf.set(
            "fs.s3a.endpoint.region",
            credentials.credentials().getOrDefault(StorageCredentialKeys.REGION, defaultS3Region));
      }
      case AZURE -> {
        conf.set("fs.azure.account.auth.type", "SAS");
        conf.set("fs.azure.sas.fixed.token", credentials.require(StorageCredentialKeys.SAS_TOKEN));
      }
      case GCP -> {
        conf.setClass(
            "fs.gs.auth.access.token.provider.impl",
            VendedGcsToken.class,
            AccessTokenProvider.class);
        conf.set(VendedGcsToken.TOKEN, credentials.require(StorageCredentialKeys.OAUTH_TOKEN));
        if (credentials.expiration() != null) {
          conf.setLong(VendedGcsToken.EXPIRES_AT, credentials.expiration().toEpochMilli());
        }
      }
    }
    return conf;
  }

  /**
   * A location as Hadoop addresses it: {@code s3a}, while catalogs report {@code s3} and older ones
   * {@code s3n}. Everything else is passed through — {@code abfss}, {@code gs} and {@code wasbs} are
   * what Hadoop calls them too — including bare paths, which the local filesystem handles. The
   * trailing slash goes, so that a path under the location can be built by appending to it.
   */
  public static String path(String location) {
    if (location == null || location.isBlank()) {
      throw ApiException.notFound("the catalog reports no storage location for this table");
    }
    String trimmed =
        location.endsWith("/") ? location.substring(0, location.length() - 1) : location;
    String lower = trimmed.toLowerCase(Locale.ROOT);
    for (String scheme : List.of("s3://", "s3n://")) {
      if (lower.startsWith(scheme)) {
        return "s3a://" + trimmed.substring(scheme.length());
      }
    }
    return trimmed;
  }
}
