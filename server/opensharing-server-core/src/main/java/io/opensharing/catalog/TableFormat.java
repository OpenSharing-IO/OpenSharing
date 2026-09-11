package io.opensharing.catalog;

import java.util.Locale;

/** Physical format of a shared table, surfaced as the protocol's {@code format} field. */
public enum TableFormat {
  DELTA,
  ICEBERG,
  PARQUET;

  public String wireName() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * @return {@code null} when no format is stated, since the protocol treats it as optional; an
   *     unrecognised format is a configuration error rather than an absent one
   */
  public static TableFormat fromWireName(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "delta", "deltasharing", "delta_sharing" -> DELTA;
      case "iceberg" -> ICEBERG;
      case "parquet" -> PARQUET;
      default -> throw new IllegalArgumentException("unsupported table format '" + value + "'");
    };
  }
}
