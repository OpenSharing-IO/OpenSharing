package io.opensharing.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
