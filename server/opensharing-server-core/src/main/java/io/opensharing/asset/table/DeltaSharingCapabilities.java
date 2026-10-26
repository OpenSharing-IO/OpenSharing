package io.opensharing.asset.table;

import io.opensharing.http.ApiException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Parses {@code delta-sharing-capabilities} and chooses parquet vs delta response actions. */
public final class DeltaSharingCapabilities {

  public enum ResponseFormat {
    PARQUET,
    DELTA
  }

  public static final String HEADER = "delta-sharing-capabilities";

  private static final Set<String> KEYS =
      Set.of("responseformat", "readerfeatures", "includeendstreamaction");

  private DeltaSharingCapabilities() {}

  /**
   * Parquet when the table's {@code minReaderVersion} is 1 and the client accepts parquet, as it
   * does without a {@code responseformat}; otherwise delta.
   */
  public static ResponseFormat choose(String header, int minReaderVersion) {
    Set<ResponseFormat> accepted = responseFormats(parse(header));
    boolean parquet = accepted.isEmpty() || accepted.contains(ResponseFormat.PARQUET);
    return minReaderVersion == 1 && parquet
        ? ResponseFormat.PARQUET
        : ResponseFormat.DELTA;
  }

  /** Value for the response {@code delta-sharing-capabilities} header. */
  public static String responded(ResponseFormat format) {
    return "responseformat=" + format.name().toLowerCase(Locale.ROOT);
  }

  static Map<String, String> parse(String header) {
    if (header == null || header.isBlank()) {
      return Map.of();
    }
    Map<String, String> capabilities = new LinkedHashMap<>();
    for (String part : header.split(";")) {
      String trimmed = part.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int eq = trimmed.indexOf('=');
      if (eq <= 0) {
        throw ApiException.invalidParameter(
            "delta-sharing-capabilities must be semicolon-separated key=value pairs");
      }
      String key = trimmed.substring(0, eq).trim().toLowerCase(Locale.ROOT);
      String value = trimmed.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
      // Async query is unsupported; ignore it so those clients still get a synchronous response.
      if (key.equals("asyncquery")) {
        continue;
      }
      if (!KEYS.contains(key)) {
        throw ApiException.invalidParameter(
            "Unsupported delta-sharing-capabilities key: '" + key + "'");
      }
      if (key.equals("includeendstreamaction")
          && !value.equals("true")
          && !value.equals("false")) {
        throw ApiException.invalidParameter(
            "Unsupported " + key + ": '" + value + "'. Supported: true, false");
      }
      capabilities.put(key, value);
    }
    return capabilities;
  }

  static Set<ResponseFormat> responseFormats(Map<String, String> capabilities) {
    EnumSet<ResponseFormat> requested = EnumSet.noneOf(ResponseFormat.class);
    String formats = capabilities.get("responseformat");
    if (formats == null) {
      return requested;
    }
    if (formats.isBlank()) {
      throw ApiException.invalidParameter(
          "Unsupported responseformat: ''. Supported: parquet, delta");
    }
    for (String token : formats.split(",")) {
      String format = token.trim();
      switch (format) {
        case "parquet" -> requested.add(ResponseFormat.PARQUET);
        case "delta" -> requested.add(ResponseFormat.DELTA);
        default ->
            throw ApiException.invalidParameter(
                "Unsupported responseformat: '" + format + "'. Supported: parquet, delta");
      }
    }
    return requested;
  }
}
