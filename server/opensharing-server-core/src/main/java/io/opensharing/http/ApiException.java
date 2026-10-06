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
