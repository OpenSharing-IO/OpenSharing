package io.opensharing.serving;

import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.TableFormat;
import io.opensharing.http.ApiException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Picks the implementation that serves a table's format.
 *
 * <p>Implementations are collected rather than named, so a new format is a class and nothing else —
 * the same arrangement {@code UrlSigners} uses for storage schemes.
 */
@Component
public class TableFormatRegistry {

  private final Map<TableFormat, TableOperations> byFormat = new EnumMap<>(TableFormat.class);

  public TableFormatRegistry(List<TableOperations> implementations) {
    for (TableOperations operations : implementations) {
      TableOperations existing = byFormat.put(operations.format(), operations);
      if (existing != null) {
        throw new IllegalStateException(
            "two implementations claim the " + operations.format() + " table format");
      }
    }
  }

  /**
   * Picks by the format the catalog states now, which the caller has just asked it for, rather than by
   * the one recorded when the table was last read. The two differ when a table has been rewritten in
   * another format since, and choosing by the record would send it to an implementation that would
   * only have to refuse it — while a table converted <em>into</em> a format this serves would go on
   * being refused for as long as the record said otherwise.
   *
   * @throws ApiException as not implemented when nothing serves that format, including when the
   *     catalog states no format at all — in either case the recipient can still read the bytes through
   *     dir access mode, which is what the message says
   */
  public TableOperations forTable(SharedDataObjectEntity table, ResolvedAsset resolved) {
    TableOperations operations = byFormat.get(resolved.format());
    if (operations == null) {
      throw ApiException.notImplemented(
          "'"
              + table.getSharedAsName()
              + "' is "
              + describe(resolved.format())
              + " in the catalog, which this server does not serve through the table read "
              + "operations; call temporary-table-credentials and read its storage location directly");
    }
    return operations;
  }

  private static String describe(TableFormat format) {
    return format == null ? "of no format the catalog states" : "a " + format.wireName() + " table";
  }
}
