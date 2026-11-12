package io.opensharing.http;

/** An error with an explicit protocol error code and HTTP status. */
public class ApiException extends RuntimeException {

  private final int status;
  private final String errorCode;

  public ApiException(int status, String errorCode, String message) {
    super(message);
    this.status = status;
    this.errorCode = errorCode;
  }

  public int getStatus() {
    return status;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public static ApiException notFound(String message) {
    return new ApiException(404, ErrorCodes.RESOURCE_DOES_NOT_EXIST, message);
  }

  /**
   * The shared source table's own state, which the data provider controls, failed the request. The
   * message tells the recipient whom to contact; server-side detail, such as paths, stays in the
   * log.
   */
  public static ApiException sourceTable(int status, String message) {
    return new ApiException(status, ErrorCodes.RESOURCE_DOES_NOT_EXIST, message);
  }

  /** The table's files, including its log, cannot be read where the catalog says they are. */
  public static ApiException tableFilesMissing() {
    return sourceTable(400, "table files missing. Contact the data provider.");
  }

  public static ApiException alreadyExists(String message) {
    return new ApiException(409, ErrorCodes.RESOURCE_ALREADY_EXISTS, message);
  }

  public static ApiException conflict(String message) {
    return new ApiException(409, ErrorCodes.RESOURCE_CONFLICT, message);
  }

  public static ApiException invalidParameter(String message) {
    return new ApiException(400, ErrorCodes.INVALID_PARAMETER_VALUE, message);
  }

  public static ApiException permissionDenied(String message) {
    return new ApiException(403, ErrorCodes.PERMISSION_DENIED, message);
  }

  public static ApiException unauthenticated(String message) {
    return new ApiException(401, ErrorCodes.UNAUTHENTICATED, message);
  }

  public static ApiException notImplemented(String message) {
    return new ApiException(501, ErrorCodes.NOT_IMPLEMENTED, message);
  }
}
