package io.opensharing.asset.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The one rule about whether a path is a shared table's own, asked by every format, and the one
 * about whether it is on storage reached without a credential.
 */
class StoragePathsTest {

  private static final String ROOT = "s3a://lake/sales/orders";

  @Test
  void acceptsWhatIsUnderTheTablesOwnRoot() {
    assertTrue(StoragePaths.isInside(ROOT + "/country=NL/part-0.parquet", ROOT));
    assertTrue(
        StoragePaths.isInside(ROOT + "/metadata/v2.metadata.json", ROOT + "/"),
        "a root the catalog wrote with a trailing slash names the same directory");
  }

  @Test
  void refusesWhatIsBesideItOrAboveIt() {
    assertFalse(StoragePaths.isInside("s3a://lake/sales/payroll/part-0.parquet", ROOT));
    assertFalse(
        StoragePaths.isInside("s3a://lake/sales/orders-archive/part-0.parquet", ROOT),
        "a sibling whose name merely starts the same way is not inside");
    assertFalse(StoragePaths.isInside(ROOT + "/../payroll/part-0.parquet", ROOT));
    assertFalse(StoragePaths.isInside(null, ROOT));
    assertFalse(StoragePaths.isInside(ROOT + "/part-0.parquet", null));
  }

  /**
   * Every spelling of a path on this machine counts, because a catalog picks its own: Unity Catalog
   * writes {@code file:///tmp/orders}, Delta Kernel hands back {@code file:/tmp/orders}, and a
   * catalog file may simply say {@code /tmp/orders}.
   */
  @Test
  void readsEveryLocalSpellingAsLocal() {
    assertTrue(StoragePaths.isLocal("/tmp/lake/orders"));
    assertTrue(StoragePaths.isLocal("file:/tmp/lake/orders"));
    assertTrue(StoragePaths.isLocal("file:///tmp/lake/orders"));
    assertTrue(StoragePaths.isLocal("FILE:///tmp/lake/orders"));
  }

  @Test
  void readsEveryOtherSchemeAsStorageWithAGrantBehindIt() {
    assertFalse(StoragePaths.isLocal(ROOT));
    assertFalse(StoragePaths.isLocal("s3://lake/sales/orders"));
    assertFalse(StoragePaths.isLocal("gs://lake/sales/orders"));
    assertFalse(StoragePaths.isLocal("abfss://lake@acct.dfs.core.windows.net/orders"));
    assertFalse(StoragePaths.isLocal("files3://not-a-local-scheme/orders"));
    assertFalse(StoragePaths.isLocal(null));
    assertFalse(StoragePaths.isLocal("  "));
  }

  /** Only a whole segment climbs; a file whose name happens to hold two dots does not. */
  @Test
  void readsOnlyAWholeSegmentAsClimbingOut() {
    assertTrue(StoragePaths.climbsOut("orders/../payroll/part-0.parquet"));
    assertFalse(StoragePaths.climbsOut("orders/metadata/v2..metadata.json"));
    assertFalse(StoragePaths.climbsOut("orders/part-0.parquet"));
  }
}
