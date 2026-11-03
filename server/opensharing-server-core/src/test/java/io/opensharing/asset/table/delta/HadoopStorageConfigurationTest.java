package io.opensharing.asset.table.delta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.exception.CatalogException;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.jupiter.api.Test;

class HadoopStorageConfigurationTest {

  @Test
  void readsR2AsS3AtTheAccountEndpoint() throws IOException {
    String location = "r2://bucket@account.r2.cloudflarestorage.com/table";
    Configuration configuration =
        HadoopStorageConfiguration.from(r2Credentials(location), location);

    assertEquals("s3a://bucket/table", HadoopStorageConfiguration.kernelPath(location));
    assertEquals(
        "s3a://bucket/",
        HadoopStorageConfiguration.kernelPath("r2://bucket@account.r2.cloudflarestorage.com"));
    assertEquals("https://account.r2.cloudflarestorage.com", configuration.get("fs.s3a.endpoint"));
    assertEquals("us-east-1", configuration.get("fs.s3a.endpoint.region"));
    assertEquals("true", configuration.get("fs.s3a.path.style.access"));
    assertEquals("r2key", configuration.get("fs.s3a.access.key"));
    assertEquals("session", configuration.get("fs.s3a.session.token"));
    assertOpens("org.apache.hadoop.fs.s3a.S3AFileSystem", location, configuration);
  }

  @Test
  void rejectsR2CredentialsForALocationOutsideR2() {
    String location = "s3://bucket/table";

    assertThrows(
        CatalogException.class,
        () -> HadoopStorageConfiguration.from(r2Credentials(location), location));
  }

  @Test
  void readsWasbThroughAbfsAtTheBlobHost() throws IOException {
    String location = "wasbs://container@account.blob.core.windows.net/table";
    String host = "account.blob.core.windows.net";
    Configuration configuration =
        HadoopStorageConfiguration.from(sasCredentials(location), location);

    assertEquals(
        "abfss://container@" + host + "/table", HadoopStorageConfiguration.kernelPath(location));
    assertEquals(
        "abfss://container@" + host + "/table",
        HadoopStorageConfiguration.kernelPath("wasb://container@" + host + "/table"));
    assertEquals("SAS", configuration.get("fs.azure.account.auth.type." + host));
    assertEquals(
        "sv=2020-12-06&sig=catalog", configuration.get("fs.azure.sas.fixed.token." + host));
    assertEquals("false", configuration.get("fs.azure.account.hns.enabled." + host));
    assertOpens("org.apache.hadoop.fs.azurebfs.SecureAzureBlobFileSystem", location, configuration);
  }

  /** Hadoop's connectors read their settings when they open, before any request to storage. */
  private static void assertOpens(String fileSystemClass, String location, Configuration settings)
      throws IOException {
    Path root = new Path(HadoopStorageConfiguration.kernelPath(location));
    try (FileSystem fileSystem = FileSystem.newInstance(root.toUri(), settings)) {
      assertEquals(fileSystemClass, fileSystem.getClass().getName());
    }
  }

  private static StorageCredentials r2Credentials(String location) {
    return new StorageCredentials(
        location,
        CloudProvider.R2,
        Map.of(
            StorageCredentials.ACCESS_KEY_ID, "r2key",
            StorageCredentials.SECRET_ACCESS_KEY, "r2secret",
            StorageCredentials.SESSION_TOKEN, "session"),
        Instant.now().plus(Duration.ofHours(1)));
  }

  private static StorageCredentials sasCredentials(String location) {
    return new StorageCredentials(
        location,
        CloudProvider.AZURE,
        Map.of(StorageCredentials.SAS_TOKEN, "sv=2020-12-06&sig=catalog"),
        Instant.now().plus(Duration.ofHours(1)));
  }
}
