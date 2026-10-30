package io.opensharing.asset.table;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Hadoop-style storage locations, such as {@code s3://bucket/key}, compared regardless of trailing
 * slashes and split without requiring them to be legal {@code java.net.URI}s.
 */
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

  public static String[] bucketAndKey(String path) {
    String rest = afterScheme(path);
    int slash = rest.indexOf('/');
    if (slash <= 0 || slash == rest.length() - 1) {
      throw new IllegalStateException("'" + path + "' is not a bucket/object path");
    }
    return new String[] {rest.substring(0, slash), rest.substring(slash + 1)};
  }

  public static AzureBlob azureBlob(String path) {
    String rest = afterScheme(path);
    int at = rest.indexOf('@');
    int slash = rest.indexOf('/');
    if (at <= 0 || slash < 0 || at > slash) {
      throw new IllegalStateException(
          "'" + path + "' is not an Azure path of the form scheme://container@account.host/blob");
    }
    return new AzureBlob(
        rest.substring(0, at), rest.substring(at + 1, slash), rest.substring(slash + 1));
  }

  public static String encodePath(String key) {
    StringBuilder path = new StringBuilder();
    for (String segment : key.split("/", -1)) {
      path.append('/').append(percentEncode(segment));
    }
    return path.toString();
  }

  private static String withoutTrailingSlash(String location) {
    return location.replaceAll("/+$", "");
  }

  private static String afterScheme(String path) {
    int separator = path.indexOf("://");
    if (separator < 0) {
      throw new IllegalStateException("'" + path + "' has no storage scheme");
    }
    return path.substring(separator + 3);
  }

  private static String percentEncode(String value) {
    StringBuilder encoded = new StringBuilder(value.length());
    for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
      char c = (char) (b & 0xFF);
      if ((c >= 'A' && c <= 'Z')
          || (c >= 'a' && c <= 'z')
          || (c >= '0' && c <= '9')
          || c == '-'
          || c == '.'
          || c == '_'
          || c == '~') {
        encoded.append(c);
      } else {
        encoded.append('%').append(String.format(Locale.ROOT, "%02X", b & 0xFF));
      }
    }
    return encoded.toString();
  }

  public record AzureBlob(String container, String host, String blob) {}
}
