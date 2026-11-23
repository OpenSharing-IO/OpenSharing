package io.opensharing.asset.table;

import io.opensharing.catalog.DataSourceFormat;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.TableProperties;
import java.util.List;

/** Access modes supported by a table of a given format. */
final class TableAccessModes {

  private TableAccessModes() {}

  /**
   * Delta tables support QueryTable and temporary credentials. Iceberg tables have no Delta log to
   * serve files from, so recipients read their files directly, with temporary credentials.
   */
  static List<String> forFormat(DataSourceFormat format) {
    if (format == null) {
      return null;
    }
    return switch (format) {
      case DELTA -> List.of("url", "dir");
      case ICEBERG -> List.of("dir");
    };
  }

  /** The access modes of {@code table} by the format the catalog reports for it. */
  static List<String> forTable(ResolvedAsset table) {
    return forFormat(
        table.additionalProperties() instanceof TableProperties properties
            ? properties.dataSourceFormat()
            : null);
  }
}
