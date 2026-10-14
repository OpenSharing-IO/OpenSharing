package io.opensharing.asset.table.signer;

import io.opensharing.http.ApiException;

/** Splits a Hadoop-style object path without requiring it to be a legal {@code java.net.URI}. */
final class StoragePaths {

  private StoragePaths() {}

  static String afterScheme(String path) {
    int separator = path.indexOf("://");
    if (separator < 0) {
      throw ApiException.invalidParameter("'" + path + "' has no storage scheme");
    }
    return path.substring(separator + 3);
  }

  static String[] bucketAndKey(String path) {
    String rest = afterScheme(path);
    int slash = rest.indexOf('/');
    if (slash <= 0 || slash == rest.length() - 1) {
      throw ApiException.invalidParameter("'" + path + "' is not a bucket/object path");
    }
    return new String[] {rest.substring(0, slash), rest.substring(slash + 1)};
  }

  static AzureBlob azureBlob(String path) {
    String rest = afterScheme(path);
    int at = rest.indexOf('@');
    int slash = rest.indexOf('/');
    if (at <= 0 || slash < 0 || at > slash) {
      throw ApiException.invalidParameter(
          "'" + path + "' is not an Azure path of the form scheme://container@account.host/blob");
    }
    return new AzureBlob(
        rest.substring(0, at), rest.substring(at + 1, slash), rest.substring(slash + 1));
  }

  record AzureBlob(String container, String host, String blob) {}
}
