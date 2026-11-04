package io.opensharing.asset.table;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Hadoop-style storage locations, such as {@code s3://bucket/key}, compared regardless of trailing
 * slashes and split without requiring them to be legal {@code java.net.URI}s.
 */
public final class StorageLocations {

  public static final Set<String> S3_SCHEMES = Set.of("s3", "s3a", "s3n");

  /** Azure file URLs are signed only for abfs:// and abfss://, so WASB tables are not read. */
  public static final Set<String> AZURE_SCHEMES = Set.of("abfs", "abfss");

  public static final Set<String> GCS_SCHEMES = Set.of("gs");

  public static final Set<String> R2_SCHEMES = Set.of("r2");

  private StorageLocations() {}

  /** The lowercase scheme of {@code location}, which is a local file when it has none. */
  public static String scheme(String location) {
    int separator = location.indexOf("://");
    return separator < 0 ? "file" : location.substring(0, separator).toLowerCase(Locale.ROOT);
  }

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

  /**
   * An R2 path, {@code r2://bucket@account.r2.cloudflarestorage.com/key}, whose host is the
   * account's S3 endpoint. The key is empty at the bucket root.
   */
  public static R2Object r2Object(String path) {
    String rest = afterScheme(path);
    int slash = rest.indexOf('/');
    String authority = slash < 0 ? rest : rest.substring(0, slash);
    int at = authority.indexOf('@');
    if (at <= 0 || at == authority.length() - 1) {
      throw new IllegalStateException(
          "'" + path + "' is not an R2 path of the form r2://bucket@account.host/key");
    }
    return new R2Object(
        authority.substring(0, at),
        authority.substring(at + 1),
        slash < 0 ? "" : rest.substring(slash + 1));
  }

  public static String encodePath(String key) {
    StringBuilder path = new StringBuilder();
    for (String segment : key.split("/", -1)) {
      path.append('/').append(percentEncode(segment));
    }
    return path.toString();
  }

  private static String withoutTrailingSlash(String location) {
    int end = location.length();
    while (end > 0 && location.charAt(end - 1) == '/') {
      end--;
    }
    return location.substring(0, end);
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

  public record R2Object(String bucket, String host, String key) {}
}
