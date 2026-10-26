package io.opensharing.catalog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StorageCredentialsCoversTest {

  @Test
  void coversItsPrefixAndLocationsBelowItIgnoringTrailingSlashes() {
    StorageCredentials slashed = credentials("s3://bucket/table/");
    StorageCredentials unslashed = credentials("s3://bucket/table");
    for (StorageCredentials credentials : new StorageCredentials[] {slashed, unslashed}) {
      assertTrue(credentials.covers("s3://bucket/table"));
      assertTrue(credentials.covers("s3://bucket/table/"));
      assertTrue(credentials.covers("s3://bucket/table/_delta_log/"));
      assertFalse(credentials.covers("s3://bucket/table2"));
      assertFalse(credentials.covers("s3://bucket/"));
    }
    assertFalse(credentials("").covers("s3://bucket/table"));
  }

  private static StorageCredentials credentials(String prefix) {
    return new StorageCredentials(
        prefix, CloudProvider.AWS, Map.of(), Instant.parse("2026-01-01T00:00:00Z"));
  }
}
