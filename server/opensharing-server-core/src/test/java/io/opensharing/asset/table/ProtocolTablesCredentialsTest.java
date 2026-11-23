package io.opensharing.asset.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.exception.CatalogException;
import io.opensharing.share.ShareEntity;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProtocolTablesCredentialsTest {

  @Test
  void rejectsAnEmptyCatalogCredentialResponse() {
    // Only the catalog credential call is involved; no database or Delta reader is needed.
    ProtocolTables tables = tablesReturning(List.of());

    // Missing credentials are a catalog error rather than a successful empty response.
    CatalogException failure =
        assertThrows(
            CatalogException.class,
            () -> tables.readCredentialsForSharedTable(share(), table("s3://bucket/t/")));

    // Identify the affected table in the catalog error.
    assertTrue(failure.getMessage().contains("main.lake.trips"));
  }

  @Test
  void keepsCredentialsForATableThatMayHaveMoved() {
    // A nonempty response may reflect the table's current, moved location.
    List<StorageCredentials> credentials = List.of(credential("s3://bucket/moved/"));
    // Isolate the catalog response from unrelated table operations.
    ProtocolTables tables = tablesReturning(credentials);

    // A possible move remains a warning, not a catalog failure.
    assertEquals(
        credentials, tables.readCredentialsForSharedTable(share(), table("s3://bucket/t/")));
  }

  @Test
  void acceptsS3CredentialsForAnR2RecordedLocation() {
    // Iceberg clients address R2 files with s3:// paths.
    List<StorageCredentials> credentials = List.of(credential("s3://bucket/t/"));
    // Return the normalized credential prefix from the catalog stub.
    ProtocolTables tables = tablesReturning(credentials);

    // An R2 recorded root and an S3 credential can describe the same directory.
    assertEquals(
        credentials,
        tables.readCredentialsForSharedTable(
            share(), table("r2://bucket@acct.r2.cloudflarestorage.com/t/")));
  }

  @Test
  void rejectsMalformedPrefixesBeforeCheckingForMoves() {
    // A bad prefix must not escape from the move check as an unchecked error.
    for (String prefix : new String[] {null, " ", "r2://bucket/table/"}) {
      // Isolate the catalog response from unrelated table operations.
      ProtocolTables tables = tablesReturning(List.of(credential(prefix)));
      // Invalid catalog credentials are reported as catalog errors.
      assertThrows(
          CatalogException.class,
          () -> tables.readCredentialsForSharedTable(share(), table("s3://bucket/t/")));
    }
  }

  private static ProtocolTables tablesReturning(List<StorageCredentials> credentials) {
    CatalogConnector catalog =
        (CatalogConnector)
            Proxy.newProxyInstance(
                CatalogConnector.class.getClassLoader(),
                new Class<?>[] {CatalogConnector.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("getStorageCredentials")) {
                    return credentials;
                  }
                  throw new AssertionError("unexpected catalog call: " + method.getName());
                });
    return new ProtocolTables(catalog, "server", null, null, null, null, null);
  }

  private static ShareEntity share() {
    ShareEntity share = new ShareEntity();
    share.setName("share");
    share.setOwnerId("owner");
    return share;
  }

  private static SharedDataObjectEntity table(String location) {
    SharedDataObjectEntity table = new SharedDataObjectEntity();
    table.setFullName("main.lake.trips");
    table.setStorageLocation(location);
    return table;
  }

  private static StorageCredentials credential(String prefix) {
    return new StorageCredentials(
        prefix,
        CloudProvider.AWS,
        Map.of(
            StorageCredentials.ACCESS_KEY_ID, "key",
            StorageCredentials.SECRET_ACCESS_KEY, "secret"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }
}
