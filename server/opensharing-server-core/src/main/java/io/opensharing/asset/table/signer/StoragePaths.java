package io.opensharing.asset.table.signer;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Splits a Hadoop-style object path without requiring it to be a legal {@code java.net.URI}. */
final class StoragePaths {

  private StoragePaths() {}

  static String afterScheme(String path) {
    int separator = path.indexOf("://");
    if (separator < 0) {
      throw new IllegalStateException("'" + path + "' has no storage scheme");
    }
    return path.substring(separator + 3);
  }

  static String[] bucketAndKey(String path) {
    String rest = afterScheme(path);
    int slash = rest.indexOf('/');
    if (slash <= 0 || slash == rest.length() - 1) {
      throw new IllegalStateException("'" + path + "' is not a bucket/object path");
    }
    return new String[] {rest.substring(0, slash), rest.substring(slash + 1)};
  }

  static AzureBlob azureBlob(String path) {
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

  static String encodePath(String key) {
    StringBuilder path = new StringBuilder();
    for (String segment : key.split("/", -1)) {
      path.append('/').append(percentEncode(segment));
    }
    return path.toString();
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

  record AzureBlob(String container, String host, String blob) {}
}
