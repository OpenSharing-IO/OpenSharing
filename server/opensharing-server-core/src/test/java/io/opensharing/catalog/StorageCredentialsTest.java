package io.opensharing.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StorageCredentialsTest {

  @Test
  void toStringListsKeysButNotSecrets() {
    StorageCredentials credentials =
        new StorageCredentials(
            "s3://bucket/table/",
            CloudProvider.AWS,
            Map.of(
                StorageCredentials.SECRET_ACCESS_KEY, "secret",
                StorageCredentials.ACCESS_KEY_ID, "key-id"),
            Instant.parse("2026-01-01T00:00:00Z"));

    assertEquals(
        "StorageCredentials[prefix=s3://bucket/table/, provider=AWS,"
            + " credentials=[accessKeyId, secretAccessKey], expiration=2026-01-01T00:00:00Z]",
        credentials.toString());
  }
}
