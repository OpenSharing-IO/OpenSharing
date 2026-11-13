package io.opensharing.asset.table;

import io.delta.kernel.internal.actions.Protocol;
import io.opensharing.http.ApiException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
   * The Delta table properties that stand for reader features, in the order they are checked, each
   * with the reader feature that covers it and the value that turns the feature off.
   */
  private static final List<PropertyWithOffValue> FEATURE_PROPERTIES =
      List.of(
          new PropertyWithOffValue("delta.columnMapping.mode", "columnmapping", "none"),
          new PropertyWithOffValue("delta.enableDeletionVectors", "deletionvectors", "false"));

  private record PropertyWithOffValue(String property, String feature, String offValue) {}

  /** What to do about table features the client did not advertise. */
  private static final String ADVERTISE_OR_DISABLE =
      "Query with responseFormat=delta and advertise the features in delta-sharing-capabilities,"
          + " or ask your data provider to disable them.";

  /** Deletion vectors stay in a protocol after they are turned off; their property decides. */
  private static final String DELETION_VECTORS = "deletionvectors";

  /** What a client declared in {@code delta-sharing-capabilities}, parsed once per request. */
  public record Declared(Set<ResponseFormat> formats, Set<String> readerFeatures) {}

  public static Declared declared(String header) {
    Map<String, String> capabilities = parse(header);
    String value = capabilities.get("readerfeatures");
    Set<String> readerFeatures = new LinkedHashSet<>();
    if (value != null) {
      for (String token : value.split(",")) {
        readerFeatures.add(token.trim());
      }
    }
    return new Declared(responseFormats(capabilities), readerFeatures);
  }

  /**
   * A table whose Delta properties enable a reader feature the client did not advertise cannot be
   * served to it: a property may only be present with the value that turns its feature off, in any
   * case as Delta reads it. This covers the table's version {@code version}.
   */
  public static void requireSupportedProperties(
      Declared declared, Map<String, String> configuration, long version) {
    if (configuration == null || configuration.isEmpty()) {
      return;
    }
    List<String> unsupported = new ArrayList<>();
    for (PropertyWithOffValue checked : FEATURE_PROPERTIES) {
      String value = configuration.get(checked.property());
      if (value != null
          && !declared.readerFeatures().contains(checked.feature())
          && !checked.offValue().equalsIgnoreCase(value.trim())) {
        unsupported.add(checked.property());
      }
    }
    if (!unsupported.isEmpty()) {
      throw ApiException.unsupportedFeatures(unsupported, version, ADVERTISE_OR_DISABLE);
    }
  }

  /**
   * A parquet response only carries protocols with reader version 1. A client that accepts delta
   * must advertise every reader feature the table requires; one that accepts only parquet gets
   * delta for an advanced table and raises the error itself. Advertised features the server does
   * not know are ignored. Metadata does not enforce this so clients can inspect protocol before
   * querying files. {@code version} is the table version {@code protocol} is of.
   */
  public static void requireReaderFeatures(
      Declared declared, ResponseFormat format, Protocol protocol, long version) {
    Set<String> required = protocol.getReaderFeatures();
    if (format != ResponseFormat.DELTA) {
      if (protocol.getMinReaderVersion() > 1) {
        throw ApiException.unsupportedFeatures(
            required == null || required.isEmpty()
                ? List.of("reader version " + protocol.getMinReaderVersion())
                : lowerCase(required),
            version,
            ADVERTISE_OR_DISABLE);
      }
      return;
    }
    if (!declared.formats().contains(ResponseFormat.DELTA) || required == null) {
      return;
    }
    List<String> unadvertised =
        lowerCase(required).stream()
            .filter(feature -> !feature.equals(DELETION_VECTORS))
            .filter(feature -> !declared.readerFeatures().contains(feature))
            .toList();
    if (!unadvertised.isEmpty()) {
      throw ApiException.unsupportedFeatures(unadvertised, version, ADVERTISE_OR_DISABLE);
    }
  }

  private static List<String> lowerCase(Set<String> features) {
    return features.stream().map(feature -> feature.toLowerCase(Locale.ROOT)).toList();
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
}
