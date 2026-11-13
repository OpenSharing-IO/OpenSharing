package io.opensharing.http;

import java.util.Collection;
import java.util.TreeSet;

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

  /** A request the server cannot serve, such as one Kernel rejected or a table it cannot read. */
  public static ApiException badRequest(String message) {
    return new ApiException(400, ErrorCodes.BAD_REQUEST, message);
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

  /**
   * The table needs Delta features or properties that cannot be served, named in a fixed order so
   * the message is the same on every request. {@code version} is the table version they were found in, if known,
   * and {@code remedy} tells the recipient what to do about them.
   */
  public static ApiException unsupportedFeatures(
      Collection<String> features, Long version, String remedy) {
    return invalidParameter(
        "Table features "
            + String.join(", ", new TreeSet<>(features))
            + " are found in table"
            + (version == null ? "." : " version: " + version + ".")
            + " "
            + remedy);
  }
}
