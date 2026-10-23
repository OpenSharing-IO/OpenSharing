package io.opensharing.asset.table;

import io.opensharing.catalog.DataSourceFormat;
import java.util.List;

/** Access modes supported by a table of a given format. */
final class TableAccessModes {

  private TableAccessModes() {}

  /** Delta tables support QueryTable and temporary credentials. */
  static List<String> forFormat(DataSourceFormat format) {
    return format == DataSourceFormat.DELTA ? List.of("url", "dir") : null;
  }
}
