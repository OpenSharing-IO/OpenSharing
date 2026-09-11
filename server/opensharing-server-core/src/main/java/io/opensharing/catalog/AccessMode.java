package io.opensharing.catalog;

import java.util.Locale;

/** How a recipient may read a table: pre-signed URLs, or credentials for directory access. */
public enum AccessMode {
  URL,
  DIR;

  public String wireName() {
    return name().toLowerCase(Locale.ROOT);
  }
}
