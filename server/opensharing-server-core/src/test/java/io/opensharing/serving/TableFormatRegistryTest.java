package io.opensharing.serving;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.TableFormat;
import io.opensharing.http.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Which implementation serves a table, and by whose account of its format.
 *
 * <p>The question these answer is what happens when a provider converts a table after sharing it.
 * The stored snapshot then says one format and the catalog another, and choosing by the snapshot
 * would send the table to an implementation with nothing to do but refuse it — or keep refusing one
 * that has just become readable.
 */
class TableFormatRegistryTest {

  private final TableOperations delta = new Fake(TableFormat.DELTA);
  private final TableOperations iceberg = new Fake(TableFormat.ICEBERG);
  private final TableFormatRegistry registry = new TableFormatRegistry(List.of(delta, iceberg));

  @Test
  void picksByWhatTheCatalogSaysNowRatherThanWhatWasStored() {
    // Shared while it was Parquet, which nothing here serves, and Delta in the catalog since.
    SharedDataObjectEntity table = table(TableFormat.PARQUET);

    assertSame(delta, registry.forTable(table, resolvedAs(TableFormat.DELTA)));
  }

  @Test
  void sendsAConvertedTableToWhicheverServesItsNewFormat() {
    SharedDataObjectEntity table = table(TableFormat.DELTA);

    assertSame(iceberg, registry.forTable(table, resolvedAs(TableFormat.ICEBERG)));
  }

  /**
   * Converted the other way, into a format the read operations do not serve at all. The recipient is
   * told so in terms of what the table is now, and pointed at the way it can still be read.
   */
  @Test
  void refusesAFormatNothingServesAndSaysWhatToDoInstead() {
    ApiException refused =
        assertThrows(
            ApiException.class,
            () -> registry.forTable(table(TableFormat.DELTA), resolvedAs(TableFormat.PARQUET)));

    assertEquals(HttpStatus.NOT_IMPLEMENTED, refused.getStatus());
    assertTrue(refused.getMessage().contains("a parquet table in the catalog"), refused.getMessage());
    assertTrue(refused.getMessage().contains("temporary-table-credentials"), refused.getMessage());
  }

  @Test
  void refusesATableTheCatalogStatesNoFormatFor() {
    ApiException refused =
        assertThrows(
            ApiException.class,
            () -> registry.forTable(table(TableFormat.DELTA), resolvedAs(null)));

    assertEquals(HttpStatus.NOT_IMPLEMENTED, refused.getStatus());
    assertTrue(refused.getMessage().contains("no format the catalog states"), refused.getMessage());
  }

  @Test
  void refusesTwoImplementationsOfOneFormat() {
    assertThrows(
        IllegalStateException.class,
        () -> new TableFormatRegistry(List.of(delta, new Fake(TableFormat.DELTA))));
  }

  private static SharedDataObjectEntity table(TableFormat stored) {
    SharedDataObjectEntity table = new SharedDataObjectEntity();
    table.setSharedAs("sales.orders");
    table.setSourceFormat(stored);
    return table;
  }

  private static ResolvedAsset resolvedAs(TableFormat format) {
    return ResolvedAsset.builder(AssetType.TABLE, "main.sales.orders").format(format).build();
  }

  /** A stand-in for a format's implementation: the registry only ever asks what it serves. */
  private record Fake(TableFormat format) implements TableOperations {

    @Override
    public long version(
        SharedDataObjectEntity table, ResolvedAsset resolved, TableRequests.Version request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ActionStream metadata(
        SharedDataObjectEntity table, ResolvedAsset resolved, TableRequests.Metadata request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ActionStream query(
        SharedDataObjectEntity table, ResolvedAsset resolved, TableRequests.Query request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ActionStream changes(
        SharedDataObjectEntity table, ResolvedAsset resolved, TableRequests.Changes request) {
      throw new UnsupportedOperationException();
    }
  }
}
