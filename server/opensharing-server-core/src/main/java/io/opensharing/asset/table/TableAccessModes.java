package io.opensharing.asset.table;

import io.opensharing.catalog.DataSourceFormat;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.TableProperties;
import java.util.List;

/** Access modes supported by a resolved table. */
final class TableAccessModes {

  private TableAccessModes() {}

  /** Delta tables support QueryTable and temporary credentials. */
  static List<String> forTable(ResolvedAsset table) {
    if (table.additionalProperties() instanceof TableProperties properties
        && properties.dataSourceFormat() == DataSourceFormat.DELTA) {
      return List.of("url", "dir");
    }
    return null;
  }
}
