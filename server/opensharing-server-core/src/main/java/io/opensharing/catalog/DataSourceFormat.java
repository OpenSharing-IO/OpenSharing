package io.opensharing.catalog;

import java.util.Locale;

/** Physical format of a shared table. */
public enum DataSourceFormat {
  DELTA,
  ICEBERG;

  public String wireName() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** {@code null} when the format is omitted; unknown values are rejected. */
  public static DataSourceFormat fromWireName(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "delta", "deltasharing" -> DELTA;
      case "iceberg" -> ICEBERG;
      default -> throw new IllegalArgumentException("unsupported table format '" + value + "'");
    };
  }
}
