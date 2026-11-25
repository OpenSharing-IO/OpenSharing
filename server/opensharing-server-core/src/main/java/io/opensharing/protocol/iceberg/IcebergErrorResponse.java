package io.opensharing.protocol.iceberg;

/**
 * The Iceberg REST catalog's error body:
 *
 * <pre>{@code
 * {"error": {"message": "<message>", "type": "<exception name>", "code": <HTTP status>}}
 * }</pre>
 */
record IcebergErrorResponse(Error error) {

  static IcebergErrorResponse of(String message, String type, int code) {
    return new IcebergErrorResponse(new Error(message, type, code));
  }

  record Error(String message, String type, int code) {}
}
