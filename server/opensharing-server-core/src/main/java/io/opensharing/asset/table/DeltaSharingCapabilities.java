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
   * No header, or parquet only, defaults to parquet. Prefer delta whenever it is listed, including
   * {@code responseformat=delta,parquet}.
   */
  public static ResponseFormat choose(String header) {
    return responseFormats(parse(header)).contains(ResponseFormat.DELTA)
        ? ResponseFormat.DELTA
        : ResponseFormat.PARQUET;
  }

  /** Value for the response {@code delta-sharing-capabilities} header: the format actually used. */
  public static String responded(String requestHeader) {
    return choose(requestHeader) == ResponseFormat.DELTA
        ? "responseformat=delta"
        : "responseformat=parquet";
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
      // Async query is not supported; skip so clients that send asyncquery still get a sync response.
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
