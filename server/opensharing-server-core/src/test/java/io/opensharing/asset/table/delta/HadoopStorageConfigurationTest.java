package io.opensharing.asset.table.delta;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.exception.CatalogException;
import io.opensharing.http.ApiException;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
    assertEquals("auto", configuration.get("fs.s3a.endpoint.region"));
    assertEquals("true", configuration.get("fs.s3a.path.style.access"));
    assertEquals("r2key", configuration.get("fs.s3a.access.key"));
    assertEquals("session", configuration.get("fs.s3a.session.token"));
    assertOpens("org.apache.hadoop.fs.s3a.S3AFileSystem", location, configuration);
  }

  @Test
  void readsOnlySupportedStorage() {
    for (String location :
        List.of(
            "s3://bucket/table",
            "S3A://bucket/table",
            "s3n://bucket/table",
            "r2://bucket@account.r2.cloudflarestorage.com/table",
            "gs://bucket/table",
            "abfs://container@account.dfs.core.windows.net/table",
            "abfss://container@account.dfs.core.windows.net/table",
            "file:///tmp/table",
            "/tmp/table")) {
      assertDoesNotThrow(() -> HadoopStorageConfiguration.requireSupported(location), location);
    }
    for (String scheme : List.of("wasb", "wasbs", "hdfs")) {
      ApiException rejected =
          assertThrows(
              ApiException.class,
              () -> HadoopStorageConfiguration.requireSupported(scheme + "://bucket/table"));
      assertEquals(501, rejected.getStatus());
      assertEquals(
          "this build cannot read tables in '" + scheme + "' storage", rejected.getMessage());
    }
  }

  @Test
  void readsEveryS3SchemeThroughS3A() throws IOException {
    Configuration configuration = new Configuration();
    configuration.set("fs.s3a.endpoint.region", "us-west-2");

    for (String scheme : List.of("s3", "s3a", "s3n")) {
      String location = scheme + "://bucket/table";
      assertEquals("s3a://bucket/table", HadoopStorageConfiguration.kernelPath(location));
      assertOpens("org.apache.hadoop.fs.s3a.S3AFileSystem", location, configuration);
    }
  }

  @Test
  void rejectsR2CredentialsForALocationOutsideR2() {
    String location = "s3://bucket/table";

    assertThrows(
        CatalogException.class,
        () -> HadoopStorageConfiguration.from(r2Credentials(location), location));
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
}
