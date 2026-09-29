package io.opensharing.asset.table;

import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.TableFormat;
import java.util.List;

/** Access modes supported by a resolved table. */
final class TableAccessModes {

  private TableAccessModes() {}

  /** Managed and external Delta tables support QueryTable and temporary credentials. */
  static List<String> forTable(ResolvedAsset table) {
    if (table.format() != TableFormat.DELTA) {
      return null;
    }
    if ("MANAGED".equalsIgnoreCase(table.subtype())
        || "EXTERNAL".equalsIgnoreCase(table.subtype())) {
      return List.of("url", "dir");
    }
    return null;
  }
}
