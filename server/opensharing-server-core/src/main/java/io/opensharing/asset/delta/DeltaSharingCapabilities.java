package io.opensharing.asset.delta;

import io.opensharing.http.ApiException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a client says it can handle, sent as {@code delta-sharing-capabilities} on the read
 * endpoints. The header exists so the protocol can grow without breaking older clients: a client
 * states the response formats and reader features it understands, and the server answers within
 * them or refuses.
 *
 * @param responseFormats formats the client accepts, empty meaning it never said, which the protocol
 *     defines as {@code parquet}
 * @param readerFeatures Delta reader features the client can process, which decide both what may be
 *     served to it and how the table is described to it
 * @param includeEndStreamAction whether to close the stream with an explicit final action
 */
public record DeltaSharingCapabilities(
    Set<String> responseFormats, Set<String> readerFeatures, boolean includeEndStreamAction) {

  private static final String RESPONSE_FORMAT = "responseformat";
  private static final String READER_FEATURES = "readerfeatures";
  private static final String INCLUDE_END_STREAM_ACTION = "includeendstreamaction";

  /**
   * The reader and writer versions of a table with no named features, which is how a table is
   * reported to a client that reads none.
   */
  private static final int PLAIN_READER_VERSION = 1;

  private static final int PLAIN_WRITER_VERSION = 2;

  /** From this writer version on, a protocol names its features instead of implying them. */
  private static final int FEATURE_WRITER_VERSION = 7;

  /**
   * The table properties that decide whether a table needs more than parquet can say, and the reader
   * feature a client has to claim before it is served one. This is the whole list: every other
   * property is the provider's own business and never a reason to refuse a read.
   */
  private static final List<Feature> FEATURES =
      List.of(
          new Feature("delta.columnMapping.mode", "none", "columnMapping"),
          new Feature("delta.enableDeletionVectors", "false", "deletionVectors"));

  public DeltaSharingCapabilities {
    responseFormats = responseFormats == null ? Set.of() : Set.copyOf(responseFormats);
    readerFeatures = readerFeatures == null ? Set.of() : Set.copyOf(readerFeatures);
  }

  /** A client that sent no header, which the protocol says means parquet and no end-stream action. */
  public static DeltaSharingCapabilities defaults() {
    return new DeltaSharingCapabilities(Set.of(), Set.of(), false);
  }

  /** Keys and values are case-insensitive, and anything unrecognised is ignored, as the spec says. */
  public static DeltaSharingCapabilities parse(String header) {
    if (header == null || header.isBlank()) {
      return defaults();
    }
    Set<String> formats = new LinkedHashSet<>();
    Set<String> features = new LinkedHashSet<>();
    boolean endStream = false;
    for (String capability : header.split(";")) {
      String[] parts = capability.split("=", 2);
      if (parts.length != 2) {
        continue;
      }
      String key = parts[0].trim().toLowerCase(Locale.ROOT);
      String value = parts[1].trim();
      switch (key) {
        case RESPONSE_FORMAT -> formats.addAll(values(value));
        case READER_FEATURES -> features.addAll(values(value));
        case INCLUDE_END_STREAM_ACTION -> endStream = Boolean.parseBoolean(value);
        default -> {
          // Unrecognised capabilities are ignored so a newer client still gets an answer.
        }
      }
    }
    return new DeltaSharingCapabilities(formats, features, endStream);
  }

  /**
   * The format to answer this table in.
   *
   * <p>What a table needs is read from the features it has turned on, in its own properties, and not
   * from the reader version its protocol carries. The two come apart often: a table keeps naming a
   * feature in its protocol long after the property that enabled it was switched off, and refusing
   * such a table would deny parquet clients a table they can read perfectly well. This is how the
   * reference server reads it too.
   *
   * <p>Given that, a client naming one format gets that one, and a client naming both is answered in
   * parquet whenever the table reads as an ordinary one, since parquet is what every client reads.
   * Silence means parquet, which is what the protocol defines it to mean.
   *
   * <p>Two requests cannot be honoured and are refused rather than answered wrongly: any format for
   * a table using a feature the client never claimed to understand, and parquet for a table using
   * one at all, since parquet has nowhere to say so.
   */
  public DeltaResponseFormat chooseFormat(DeltaSnapshot table) {
    Set<DeltaResponseFormat> asked = formatsAsked();
    requireTheClientReadsWhatTheTableTurnedOn(table.metadata());
    DeltaResponseFormat format = settle(asked, table.protocol());
    if (format == DeltaResponseFormat.PARQUET) {
      requireParquetCanCarryIt(table.metadata());
    }
    return format;
  }

  /**
   * The protocol to report for a table, which is not always the one the log recorded. A client that
   * named no reader features is told the table is an ordinary one — which, for that client, the
   * check above has just established it is — because a reader version the client does not know would
   * have it refuse a table it can read. A client that named features is told what the log says, so it
   * can decide for itself.
   */
  public DeltaSnapshot.Protocol reportedProtocol(DeltaSnapshot.Protocol protocol) {
    if (!readerFeatures.isEmpty()) {
      return protocol;
    }
    return new DeltaSnapshot.Protocol(
        PLAIN_READER_VERSION,
        protocol.minWriterVersion() < FEATURE_WRITER_VERSION
            ? protocol.minWriterVersion()
            : PLAIN_WRITER_VERSION,
        List.of(),
        List.of());
  }

  /** Echoed back so a client can see which format it is holding, and what it must expect. */
  public String responseHeaderValue(DeltaResponseFormat format) {
    String value = RESPONSE_FORMAT + "=" + format.wireName();
    return includeEndStreamAction ? value + ";" + INCLUDE_END_STREAM_ACTION + "=true" : value;
  }

  private DeltaResponseFormat settle(
      Set<DeltaResponseFormat> asked, DeltaSnapshot.Protocol protocol) {
    if (asked.size() == 1) {
      return asked.iterator().next();
    }
    return readsAsAPlainTable(protocol) ? DeltaResponseFormat.PARQUET : DeltaResponseFormat.DELTA;
  }

  /** The formats a client will take, of the two this server writes, silence counting as parquet. */
  private Set<DeltaResponseFormat> formatsAsked() {
    if (responseFormats.isEmpty()) {
      return Set.of(DeltaResponseFormat.PARQUET);
    }
    Set<DeltaResponseFormat> asked =
        Arrays.stream(DeltaResponseFormat.values())
            .filter(format -> responseFormats.contains(format.wireName()))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    if (asked.isEmpty()) {
      throw ApiException.invalidParameter(
          "this request accepts neither response format this server answers in; ask for "
              + "responseformat=parquet, responseformat=delta, or both, rather than '"
              + String.join(",", responseFormats)
              + "'");
    }
    return asked;
  }

  /**
   * Whether the table can be described as an ordinary one. A client that named no reader features has
   * just been shown that nothing needing one is turned on, so for it the table is plain whatever
   * version the protocol carries.
   */
  private boolean readsAsAPlainTable(DeltaSnapshot.Protocol protocol) {
    return readerFeatures.isEmpty() || protocol.minReaderVersion() <= PLAIN_READER_VERSION;
  }

  /**
   * A client that cannot process a feature the table has turned on would misread the answer —
   * silently, since the files look ordinary either way. So it is refused instead, naming what is
   * missing.
   */
  private void requireTheClientReadsWhatTheTableTurnedOn(DeltaSnapshot.Metadata metadata) {
    List<String> missing =
        featuresTurnedOn(metadata).stream()
            .filter(feature -> !readerFeatures.contains(feature.toLowerCase(Locale.ROOT)))
            .toList();
    if (!missing.isEmpty()) {
      throw ApiException.invalidParameter(
          "this table has "
              + String.join(" and ", missing)
              + " turned on, which this request does not claim to read; ask for responseformat=delta "
              + "in the delta-sharing-capabilities header with readerfeatures="
              + String.join(",", missing)
              + ", or read the table through dir access mode");
    }
  }

  /**
   * Parquet lines have nowhere to say that rows have been deleted or columns renamed, so a table
   * doing either is refused rather than answered in a shape that reads as if it were not. Only a
   * client that claimed the feature and then asked for parquet alone reaches this.
   */
  private static void requireParquetCanCarryIt(DeltaSnapshot.Metadata metadata) {
    List<String> turnedOn = featuresTurnedOn(metadata);
    if (!turnedOn.isEmpty()) {
      throw ApiException.notImplemented(
          "this table has "
              + String.join(" and ", turnedOn)
              + " turned on, which the parquet response format cannot express; ask for "
              + "responseformat=delta in the delta-sharing-capabilities header, or read the table "
              + "through dir access mode");
    }
  }

  private static List<String> featuresTurnedOn(DeltaSnapshot.Metadata metadata) {
    return FEATURES.stream()
        .filter(feature -> feature.isTurnedOnIn(metadata.configuration()))
        .map(Feature::name)
        .toList();
  }

  /**
   * A reader feature as a table declares it: the property that turns it on, the value that leaves it
   * off, and the name a client claims it by.
   */
  private record Feature(String property, String offValue, String name) {

    boolean isTurnedOnIn(Map<String, String> configuration) {
      String value = configuration.get(property);
      return value != null && !value.equalsIgnoreCase(offValue);
    }
  }

  private static Set<String> values(String value) {
    return Arrays.stream(value.split(","))
        .map(part -> part.trim().toLowerCase(Locale.ROOT))
        .filter(part -> !part.isEmpty())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }
}
