package io.opensharing.asset.table;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
