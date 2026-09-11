package io.opensharing.catalog;

import java.util.Locale;

/**
 * How a recipient may read a table. {@code URL} means the server serves pre-signed file URLs via
 * the Delta Sharing query APIs; {@code DIR} means the server vends credentials scoped to the
 * table's storage location.
 */
public enum AccessMode {
  URL,
  DIR;

  public String wireName() {
    return name().toLowerCase(Locale.ROOT);
  }
}
