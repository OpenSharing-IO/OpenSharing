package io.opensharing.asset.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.exception.CatalogException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class StorageLocationsTest {

  @Test
  void prefixCoversItselfAndLocationsBelowItIgnoringTrailingSlashes() {
    for (String prefix : new String[] {"s3://bucket/table/", "s3://bucket/table"}) {
      assertTrue(StorageLocations.covers(prefix, "s3://bucket/table"));
      assertTrue(StorageLocations.covers(prefix, "s3://bucket/table/"));
      assertTrue(StorageLocations.covers(prefix, "s3://bucket/table/_delta_log/"));
      assertFalse(StorageLocations.covers(prefix, "s3://bucket/table2"));
      assertFalse(StorageLocations.covers(prefix, "s3://bucket/"));
    }
    assertFalse(StorageLocations.covers("", "s3://bucket/table"));
  }

  @Test
  void sameLocationIgnoringTrailingSlashes() {
    assertTrue(StorageLocations.same("s3://bucket/table", "s3://bucket/table//"));
    assertFalse(StorageLocations.same("s3://bucket/table", "s3://bucket/table2"));
  }

  @Test
  void comparesALongRunOfSlashesInLinearTime() {
    String slashes = "s3://bucket/table" + "/".repeat(1_000_000);
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          assertFalse(StorageLocations.same(slashes + "x", "s3://bucket/table"));
          assertTrue(StorageLocations.same(slashes, "s3://bucket/table"));
        });
  }

  @Test
  void icebergPathsUseTheR2BucketsS3Path() {
    // Preserve Hadoop path characters without URI parsing or encoding.
    assertEquals(
        "s3://bucket/table with space%/",
        StorageLocations.icebergPath(
            "r2://bucket@account.r2.cloudflarestorage.com/table with space%/"));
    // A bucket-root credential also has an S3 prefix.
    assertEquals(
        "s3://bucket/",
        StorageLocations.icebergPath("r2://bucket@account.r2.cloudflarestorage.com"));
    // Other storage schemes remain unchanged.
    assertEquals(
        "abfss://container@account.dfs.core.windows.net/table/",
        StorageLocations.icebergPath("abfss://container@account.dfs.core.windows.net/table/"));
    // Metadata written by Iceberg is covered by the catalog's R2 table root.
    assertTrue(
        StorageLocations.covers(
            StorageLocations.icebergPath("r2://bucket@account.r2.cloudflarestorage.com/t/"),
            StorageLocations.icebergPath("s3://bucket/t/metadata/v1.json")));
    // Normalizing schemes must not loosen directory boundaries.
    assertFalse(
        StorageLocations.covers(
            StorageLocations.icebergPath("r2://bucket@account.r2.cloudflarestorage.com/t/"),
            StorageLocations.icebergPath("s3://bucket/two/metadata/v1.json")));
  }

  @Test
  void rejectsAMalformedR2LocationAsACatalogFailure() {
    // An R2 location without bucket@account is a catalog error rather than an unchecked one.
    assertThrows(
        CatalogException.class, () -> StorageLocations.icebergPath("r2://no-account/table/"));
  }

  @Test
  void icebergPathsCompareS3AliasesAsS3() {
    // Spark's Hadoop file IO writes s3a:// paths for a table the catalog reports at s3://.
    assertEquals("s3://bucket/t/m.json", StorageLocations.icebergPath("s3a://bucket/t/m.json"));
    // s3n and an upper-case scheme compare the same way.
    assertEquals("s3://bucket/t/", StorageLocations.icebergPath("S3N://bucket/t/"));
    // So an s3:// table location covers metadata written at s3a://.
    assertTrue(
        StorageLocations.covers(
            StorageLocations.icebergPath("s3://bucket/t/"),
            StorageLocations.icebergPath("s3a://bucket/t/metadata/v1.json")));
  }
}
