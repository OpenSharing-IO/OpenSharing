package io.opensharing.asset.table;

/** Storage locations compared regardless of trailing slashes. */
public final class StorageLocations {

  private StorageLocations() {}

  /** Whether {@code prefix} is {@code location} or a directory above it. */
  public static boolean covers(String prefix, String location) {
    if (prefix == null || prefix.isBlank()) {
      return false;
    }
    String directory = withoutTrailingSlash(prefix);
    String path = withoutTrailingSlash(location);
    return path.equals(directory) || path.startsWith(directory + "/");
  }

  /** Whether {@code location} and {@code other} name the same location. */
  public static boolean same(String location, String other) {
    return withoutTrailingSlash(location).equals(withoutTrailingSlash(other));
  }

  private static String withoutTrailingSlash(String location) {
    return location.replaceAll("/+$", "");
  }
}
