package io.opensharing.asset.table;

import io.delta.kernel.internal.actions.Protocol;
import io.opensharing.http.ApiException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    return responded(format, false);
  }

  public static String responded(ResponseFormat format, boolean includeEndStreamAction) {
    String responded = "responseformat=" + format.name().toLowerCase(Locale.ROOT);
    return includeEndStreamAction ? responded + ";includeendstreamaction=true" : responded;
  }

  public static boolean includeEndStreamAction(String header) {
    return "true".equals(parse(header).get("includeendstreamaction"));
  }

  /**
   * A parquet response only carries protocols with reader version 1. A client that accepts delta
   * must advertise every reader feature the table requires; one that accepts only parquet gets
   * delta for an advanced table and raises the error itself. Advertised features the server does
   * not know are ignored. Metadata does not enforce this so clients can inspect protocol before
   * querying files.
   */
  public static void requireReaderFeatures(
      String header, ResponseFormat format, Protocol protocol) {
    if (format != ResponseFormat.DELTA) {
      if (protocol.getMinReaderVersion() > 1) {
        throw ApiException.invalidParameter(
            "The table requires reader features that are only available in delta format");
      }
      return;
    }
    if (!responseFormats(parse(header)).contains(ResponseFormat.DELTA)) {
      return;
    }
    Set<String> required = protocol.getReaderFeatures();
    if (required == null || required.isEmpty()) {
      return;
    }
    Set<String> advertised = advertisedReaderFeatures(header);
    for (String feature : required) {
      if (!advertised.contains(feature.toLowerCase(Locale.ROOT))) {
        throw ApiException.invalidParameter(
            "The table requires reader feature '"
                + feature
                + "' which was not specified in delta-sharing-capabilities");
      }
    }
  }

  /**
   * The capabilities this server knows. Clients advertise ones it does not, such as asyncquery,
   * and may send parts without a value; both are ignored.
   */
  static Map<String, String> parse(String header) {
    if (header == null || header.isBlank()) {
      return Map.of();
    }
    Map<String, String> capabilities = new LinkedHashMap<>();
    for (String part : header.split(";")) {
      int eq = part.indexOf('=');
      String key = eq < 0 ? "" : part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
      if (!KEYS.contains(key)) {
        continue;
      }
      String value = part.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
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

  private static Set<String> advertisedReaderFeatures(String header) {
    String value = parse(header).get("readerfeatures");
    if (value == null) {
      return Set.of();
    }
    Set<String> advertised = new LinkedHashSet<>();
    for (String token : value.split(",")) {
      advertised.add(token.trim());
    }
    return advertised;
  }
}
