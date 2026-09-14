package io.opensharing.http;

import org.springframework.http.HttpStatus;

/** An error with an explicit protocol error code and HTTP status. */
public class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final String errorCode;

  public ApiException(HttpStatus status, String errorCode, String message) {
    super(message);
    this.status = status;
    this.errorCode = errorCode;
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public static ApiException notFound(String message) {
    return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_DOES_NOT_EXIST, message);
  }

  public static ApiException alreadyExists(String message) {
    return new ApiException(HttpStatus.CONFLICT, ErrorCodes.RESOURCE_ALREADY_EXISTS, message);
  }

  /** The object exists, but its current state does not allow what was asked. */
  public static ApiException conflict(String message) {
    return new ApiException(HttpStatus.CONFLICT, ErrorCodes.RESOURCE_CONFLICT, message);
  }

  public static ApiException invalidParameter(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_PARAMETER_VALUE, message);
  }

  /** The caller is known, but is not allowed to do this. */
  public static ApiException permissionDenied(String message) {
    return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.PERMISSION_DENIED, message);
  }

  public static ApiException unauthenticated(String message) {
    return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHENTICATED, message);
  }

  public static ApiException notImplemented(String message) {
    return new ApiException(HttpStatus.NOT_IMPLEMENTED, ErrorCodes.NOT_IMPLEMENTED, message);
  }
}
