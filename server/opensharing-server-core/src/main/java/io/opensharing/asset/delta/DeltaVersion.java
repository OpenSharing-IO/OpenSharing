package io.opensharing.asset.delta;

import io.opensharing.http.ApiException;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Which version of a table to read: the latest, an explicit version, or whatever was current at a
 * point in time. The protocol lets a client name at most one of the two, which is checked here so
 * every endpoint that accepts them rejects the same way.
 */
public record DeltaVersion(Long version, Instant timestamp) {

  public DeltaVersion {
    if (version != null && timestamp != null) {
      throw ApiException.invalidParameter("version and timestamp are mutually exclusive");
    }
    if (version != null && version < 0) {
      throw ApiException.invalidParameter("version must not be negative");
    }
  }

  public static DeltaVersion latest() {
    return new DeltaVersion(null, null);
  }

  public static DeltaVersion of(long version) {
    return new DeltaVersion(version, null);
  }

  public static DeltaVersion asOf(Instant timestamp) {
    return new DeltaVersion(null, timestamp);
  }

  /** Builds from the raw query parameters, where the timestamp is an ISO-8601 instant. */
  public static DeltaVersion from(Long version, String timestamp) {
    return new DeltaVersion(version, parse(timestamp));
  }

  public boolean isLatest() {
    return version == null && timestamp == null;
  }

  /**
   * The protocol's timestamp format is ISO-8601 with a zone, e.g. {@code 2022-01-01T00:00:00Z}. A
   * malformed value is the client's mistake, so it is a bad request rather than a server error.
   */
  static Instant parse(String timestamp) {
    if (timestamp == null || timestamp.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(timestamp.trim());
    } catch (DateTimeParseException e) {
      throw ApiException.invalidParameter(
          "'" + timestamp + "' is not an ISO-8601 timestamp, e.g. 2022-01-01T00:00:00Z");
    }
  }
}
