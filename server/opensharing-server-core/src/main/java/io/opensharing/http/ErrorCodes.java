package io.opensharing.http;

/** Error codes used in {@link ErrorResponse#errorCode()}. */
public final class ErrorCodes {

  public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
  public static final String INVALID_PARAMETER_VALUE = "INVALID_PARAMETER_VALUE";
  public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
  public static final String PERMISSION_DENIED = "PERMISSION_DENIED";
  public static final String RESOURCE_DOES_NOT_EXIST = "RESOURCE_DOES_NOT_EXIST";
  public static final String RESOURCE_ALREADY_EXISTS = "RESOURCE_ALREADY_EXISTS";
  public static final String RESOURCE_CONFLICT = "RESOURCE_CONFLICT";
  public static final String CATALOG_ERROR = "CATALOG_ERROR";
  public static final String NOT_IMPLEMENTED = "NOT_IMPLEMENTED";
  public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

  private ErrorCodes() {}
}
