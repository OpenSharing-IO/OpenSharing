package io.opensharing.asset.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.cloud.hadoop.util.AccessTokenProvider;
import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.StorageCredentialKeys;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.config.OpenSharingProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.util.ReflectionUtils;
import org.junit.jupiter.api.Test;

/**
 * How the server reaches a table's storage when it has to read the table's own log: the credentials
 * each provider takes, and the spelling of a path each one wants.
 */
class HadoopStorageTest {

  private static final HadoopStorage STORAGE = new HadoopStorage(new OpenSharingProperties());

  @Test
  void addressesS3TheWayHadoopDoes() {
    assertEquals("s3a://bucket/table", HadoopStorage.path("s3://bucket/table/"));
    assertEquals("s3a://bucket/table", HadoopStorage.path("s3n://bucket/table"));
    assertEquals(
        "abfss://c@a.dfs.core.windows.net/t",
        HadoopStorage.path("abfss://c@a.dfs.core.windows.net/t"));
    assertEquals("gs://bucket/table", HadoopStorage.path("gs://bucket/table/"));
  }

  /**
   * The four filesystem families a shared table can live on, each answered by a driver this build
   * ships. Resolving the class reads no storage, so this says what a deployment has, not what it can
   * reach.
   */
  @Test
  void hasADriverForEveryStorageASharedTableCanLiveOn() throws Exception {
    for (String scheme : List.of("s3a", "abfss", "wasbs", "gs", "file")) {
      assertTrue(
          FileSystem.getFileSystemClass(scheme, new Configuration()) != null,
          "no driver answers " + scheme);
    }
  }

  @Test
  void readsS3WithTheSessionTheCatalogMinted() {
    Configuration conf =
        STORAGE.configurationFor(
            credentials(
                CloudProvider.AWS,
                Map.of(
                    StorageCredentialKeys.ACCESS_KEY_ID, "AKIAEXAMPLE",
                    StorageCredentialKeys.SECRET_ACCESS_KEY, "secret",
                    StorageCredentialKeys.SESSION_TOKEN, "session")));

    assertEquals(
        "org.apache.hadoop.fs.s3a.TemporaryAWSCredentialsProvider",
        conf.get("fs.s3a.aws.credentials.provider"),
        "the provider that reads a session triple, not one that looks for a long-lived key");
    assertEquals("AKIAEXAMPLE", conf.get("fs.s3a.access.key"));
    assertEquals("secret", conf.get("fs.s3a.secret.key"));
    assertEquals("session", conf.get("fs.s3a.session.token"));
    assertEquals(
        "us-east-1", conf.get("fs.s3a.endpoint.region"), "the configured default, none being vended");
  }

  @Test
  void readsS3InTheRegionTheCatalogNames() {
    Configuration conf =
        STORAGE.configurationFor(
            credentials(
                CloudProvider.AWS,
                Map.of(
                    StorageCredentialKeys.ACCESS_KEY_ID, "AKIAEXAMPLE",
                    StorageCredentialKeys.SECRET_ACCESS_KEY, "secret",
                    StorageCredentialKeys.SESSION_TOKEN, "session",
                    StorageCredentialKeys.REGION, "eu-west-1")));

    assertEquals("eu-west-1", conf.get("fs.s3a.endpoint.region"));
  }

  @Test
  void readsAzureWithTheSasItself() {
    Configuration conf =
        STORAGE.configurationFor(
            credentials(
                CloudProvider.AZURE, Map.of(StorageCredentialKeys.SAS_TOKEN, "sv=2021&sig=x")));

    assertEquals("SAS", conf.get("fs.azure.account.auth.type"));
    assertEquals(
        "sv=2021&sig=x",
        conf.get("fs.azure.sas.fixed.token"),
        "the vended token is the grant, so it is handed over as it is rather than minted from a key");
  }

  /**
   * Google's connector takes credentials only through a class it instantiates itself, so what is
   * asserted here is the whole round trip: the key the connector reads, the class it finds there, and
   * the token that class hands back.
   */
  @Test
  void readsGoogleCloudStorageWithTheTokenItWasVended() {
    Instant expiry = Instant.now().plusSeconds(900);
    Configuration conf =
        STORAGE.configurationFor(
            new StorageCredentials(
                "gs://bucket/table",
                CloudProvider.GCP,
                Map.of(StorageCredentialKeys.OAUTH_TOKEN, "ya29.example"),
                expiry));

    Class<? extends AccessTokenProvider> provider =
        conf.getClass("fs.gs.auth.access.token.provider.impl", null, AccessTokenProvider.class);
    assertEquals(VendedGcsToken.class, provider);
    AccessTokenProvider.AccessToken token =
        ReflectionUtils.newInstance(provider, conf).getAccessToken();
    assertEquals("ya29.example", token.getToken());
    assertEquals(expiry.toEpochMilli(), token.getExpirationTimeMilliSeconds());
  }

  @Test
  void readsWithWhateverTheDeploymentItselfCanReachWhenNothingIsVended() {
    Configuration conf = STORAGE.configurationFor(null);

    assertNull(
        conf.get("fs.s3a.access.key"),
        "no credential of the server's own is ever put in the way of the deployment's");
  }

  private static StorageCredentials credentials(
      CloudProvider provider, Map<String, String> values) {
    return new StorageCredentials("prefix", provider, values, Instant.now().plusSeconds(900));
  }
}
