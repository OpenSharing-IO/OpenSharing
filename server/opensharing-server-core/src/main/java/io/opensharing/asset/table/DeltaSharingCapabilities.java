package io.opensharing.asset.table;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Parses {@code delta-sharing-capabilities} and chooses parquet vs delta response actions. */
public final class DeltaSharingCapabilities {

  public enum ResponseFormat {
    PARQUET,
    DELTA
  }

  private DeltaSharingCapabilities() {}

  /**
   * No header, or parquet only, defaults to parquet. Delta only must be delta. When both are listed,
   * parquet is used unless the table needs delta-format reader features.
   */
  public static ResponseFormat choose(String header, boolean requiresDeltaFormat) {
    Set<ResponseFormat> requested = responseFormats(header);
    if (requested.isEmpty()) {
      return ResponseFormat.PARQUET;
    }
    if (requested.contains(ResponseFormat.DELTA) && requested.contains(ResponseFormat.PARQUET)) {
      return requiresDeltaFormat ? ResponseFormat.DELTA : ResponseFormat.PARQUET;
    }
    return requested.contains(ResponseFormat.DELTA)
        ? ResponseFormat.DELTA
        : ResponseFormat.PARQUET;
  }

  static Set<ResponseFormat> responseFormats(String header) {
    EnumSet<ResponseFormat> requested = EnumSet.noneOf(ResponseFormat.class);
    if (header == null || header.isBlank()) {
      return requested;
    }
    for (String part : header.split(";")) {
      int eq = part.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String key = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
      if (!key.equals("responseformat")) {
        continue;
      }
      for (String token : part.substring(eq + 1).split(",")) {
        switch (token.trim().toLowerCase(Locale.ROOT)) {
          case "parquet" -> requested.add(ResponseFormat.PARQUET);
          case "delta" -> requested.add(ResponseFormat.DELTA);
          default -> {}
        }
      }
    }
    return requested;
  }
}
