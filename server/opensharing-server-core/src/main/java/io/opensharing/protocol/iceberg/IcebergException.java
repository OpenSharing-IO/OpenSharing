package io.opensharing.protocol.iceberg;

/**
 * A failure of the Iceberg REST catalog with the error {@code type} an Iceberg client acts on. Its
 * clients tell a missing namespace from a missing table only by {@code type}, so where that
 * matters the catalog throws this instead of a protocol error.
 */
public final class IcebergException extends RuntimeException {

  static final String NO_SUCH_NAMESPACE = "NoSuchNamespaceException";
  static final String NO_SUCH_TABLE = "NoSuchTableException";
  static final String BAD_REQUEST = "BadRequestException";

  private final int status;
  private final String type;

  IcebergException(int status, String type, String message) {
    super(message);
    this.status = status;
    this.type = type;
  }

  static IcebergException noSuchNamespace(String message) {
    return new IcebergException(404, NO_SUCH_NAMESPACE, message);
  }

  static IcebergException noSuchTable(String message) {
    return new IcebergException(404, NO_SUCH_TABLE, message);
  }

  static IcebergException badRequest(String message) {
    return new IcebergException(400, BAD_REQUEST, message);
  }

  static IcebergException notFound(String message) {
    return new IcebergException(404, IcebergErrorHandler.typeOf(404), message);
  }

  static IcebergException forbidden(String message) {
    return new IcebergException(403, IcebergErrorHandler.typeOf(403), message);
  }

  int status() {
    return status;
  }

  String type() {
    return type;
  }
}
