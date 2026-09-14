package io.opensharing.serving;

import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.TableFormat;

/**
 * The protocol's table read operations, as one table format answers them.
 *
 * <p>These four endpoints belong to a table, not to a format: {@code spec/protocols/TABLES.md} defines
 * them for anything a share can hold, and a recipient asking for a table's metadata should not have to
 * know what the bytes underneath are. So the endpoint resolves the table and asks whichever
 * implementation claims its format, and a format nobody implements yet is a server-side gap — answered
 * as such, rather than as the recipient having asked something invalid.
 *
 * <p>An implementation is handed the resolution dispatch was decided by, and can rely on its format
 * being the one it serves. The endpoint resolves the table against the catalog before choosing, so a
 * table rewritten in another format since it was last read goes to the implementation for the format
 * it is now, not the one it was — and the resolution travels with it rather than being asked for
 * twice.
 */
public interface TableOperations {

  /** The format this serves. One implementation each, resolved by {@link TableFormatRegistry}. */
  TableFormat format();

  long version(
      SharedDataObjectEntity table, ResolvedAsset resolved, TableRequests.Version request);

  ActionStream metadata(
      SharedDataObjectEntity table, ResolvedAsset resolved, TableRequests.Metadata request);

  ActionStream query(
      SharedDataObjectEntity table, ResolvedAsset resolved, TableRequests.Query request);

  ActionStream changes(
      SharedDataObjectEntity table, ResolvedAsset resolved, TableRequests.Changes request);
}
