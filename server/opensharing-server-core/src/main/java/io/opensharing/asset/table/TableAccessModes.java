package io.opensharing.asset.table;

import io.opensharing.catalog.DataSourceFormat;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.TableProperties;
import java.util.List;

/** Access modes supported by a table of a given format. */
final class TableAccessModes {

  private TableAccessModes() {}

  /** Delta tables support QueryTable and temporary credentials. */
  static List<String> forFormat(DataSourceFormat format) {
    return format == DataSourceFormat.DELTA ? List.of("url", "dir") : null;
  }

  /** The access modes of {@code table} by the format the catalog reports for it. */
  static List<String> forTable(ResolvedAsset table) {
    return forFormat(
        table.additionalProperties() instanceof TableProperties properties
            ? properties.dataSourceFormat()
            : null);
  }
}
