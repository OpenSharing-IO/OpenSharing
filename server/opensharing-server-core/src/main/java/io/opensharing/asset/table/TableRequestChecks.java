package io.opensharing.asset.table;

import io.opensharing.http.ApiException;
import java.util.Arrays;
import java.util.Objects;

/**
 * The checks and messages for a table request's version, timestamp and range parameters, which
 * answer INVALID_PARAMETER_VALUE.
 */
public final class TableRequestChecks {

  private TableRequestChecks() {}

  /** {@code value} as a version, or null if absent. */
  public static Long parseVersion(String name, String value) {
    if (value == null) {
      return null;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException invalid) {
      throw ApiException.invalidParameter(name + " is not a valid number.");
    }
  }

  public static void requireNotNegative(String name, Long version) {
    if (version != null && version < 0) {
      throw ApiException.invalidParameter(name + " cannot be negative.");
    }
  }

  /** At most one of the parameters {@code names} lists can be set, as {@code values} say. */
  public static void requireAtMostOne(String names, Object... values) {
    if (Arrays.stream(values).filter(Objects::nonNull).count() > 1) {
      throw ApiException.invalidParameter("Please only provide one of: " + names);
    }
  }

  /** A query's starting version cannot be after its ending version. */
  public static void requireOrderedVersions(Long startingVersion, Long endingVersion) {
    if (startingVersion != null && endingVersion != null && endingVersion < startingVersion) {
      throw ApiException.invalidParameter(
          "startingVersion("
              + startingVersion
              + ") must be smaller than or equal to endingVersion("
              + endingVersion
              + ").");
    }
  }

  /** A Change Data Feed read needs a starting version or timestamp. */
  public static void requireStart(Object startingVersion, Object startingTimestamp) {
    if (startingVersion == null && startingTimestamp == null) {
      throw ApiException.invalidParameter(
          "No startingVersion or startingTimestamp provided for CDF read. Set one of them on the"
              + " request's cdfOptions to bound the CDC range.");
    }
  }

  /** A Change Data Feed range cannot end before it starts. */
  public static void requireOrderedRange(long start, long end) {
    if (end < start) {
      throw ApiException.invalidParameter(
          "CDF range from startingVersion "
              + start
              + " to endingVersion "
              + end
              + " was invalid. End cannot be before start.");
    }
  }

  /** At most one of a Change Data Feed range's version and timestamp, for its {@code bound}. */
  public static void requireOneBoundary(String bound, Object version, Object timestamp) {
    if (version != null && timestamp != null) {
      throw ApiException.invalidParameter(
          "Multiple "
              + bound
              + " arguments provided for CDF read. Please provide one of either "
              + bound
              + "Timestamp or "
              + bound
              + "Version.");
    }
  }

  /** The versions of a Change Data Feed range that the table has: not after {@code latest}. */
  public static void requireStartAtMostLatest(long start, long latest) {
    if (start > latest) {
      throw ApiException.invalidParameter(
          "Provided Start version("
              + start
              + ") for reading change data is invalid. Start version cannot be greater than the"
              + " latest version of the table("
              + latest
              + ").");
    }
  }

  public static void requireEndAtMostLatest(long end, long latest) {
    if (end > latest) {
      throw ApiException.invalidParameter(
          "Provided End version("
              + end
              + ") for reading change data is invalid. End version cannot be greater than the"
              + " latest version of the table("
              + latest
              + ").");
    }
  }
}
