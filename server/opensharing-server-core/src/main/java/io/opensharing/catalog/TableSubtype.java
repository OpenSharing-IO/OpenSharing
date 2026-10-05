package io.opensharing.catalog;

import java.util.Locale;

/**
 * The kinds of table OpenSharing can share: those whose data files it can read from storage. Views,
 * materialized views, and streaming tables are not supported.
 */
public enum TableSubtype {
  /** Data files are owned and laid out by the catalog. */
  MANAGED,
  /** Data files live at a location registered by the user. */
  EXTERNAL;

  /** {@code null} when the subtype is omitted; matches case-insensitively, rejects others. */
  public static TableSubtype parse(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      throw new IllegalArgumentException("unsupported table subtype '" + value + "'");
    }
  }
}
