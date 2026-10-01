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

  public static final String HEADER = "delta-sharing-capabilities";

  private DeltaSharingCapabilities() {}

  /**
   * No header, or parquet only, defaults to parquet. Prefer delta whenever it is listed, including
   * {@code responseformat=delta,parquet}.
   */
  public static ResponseFormat choose(String header) {
    return responseFormats(header).contains(ResponseFormat.DELTA)
        ? ResponseFormat.DELTA
        : ResponseFormat.PARQUET;
  }

  /** Value for the response {@code delta-sharing-capabilities} header: the format actually used. */
  public static String responded(String requestHeader) {
    return choose(requestHeader) == ResponseFormat.DELTA
        ? "responseformat=delta"
        : "responseformat=parquet";
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
