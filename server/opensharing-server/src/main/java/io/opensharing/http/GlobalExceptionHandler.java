package io.opensharing.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Renders every failure as {@code {errorCode, message}}. */
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handle(Exception e) {
    ApiFailure failure = failureOf(e);
    return ResponseEntity.status(failure.status())
        .body(new ErrorResponse(failure.errorCode(), failure.message()));
  }

  /** Maps Spring MVC's own failures; everything else is mapped by {@link ApiFailure}. */
  static ApiFailure failureOf(Exception e) {
    return switch (e) {
      case MethodArgumentNotValidException invalid ->
          new ApiFailure(400, ErrorCodes.INVALID_PARAMETER_VALUE, describe(invalid));
      case HandlerMethodValidationException ignored ->
          new ApiFailure(400, ErrorCodes.INVALID_PARAMETER_VALUE, "request validation failed");
      case MethodArgumentTypeMismatchException mistyped ->
          new ApiFailure(400, ErrorCodes.INVALID_PARAMETER_VALUE, describe(mistyped));
      case MissingServletRequestParameterException missing ->
          new ApiFailure(
              400, ErrorCodes.INVALID_PARAMETER_VALUE, missing.getParameterName() + " is required");
      case HttpMessageNotReadableException unreadable ->
          unreadable.getCause() instanceof JsonProcessingException json
              ? ApiFailure.of(json)
              : new ApiFailure(400, ErrorCodes.MALFORMED_REQUEST, "request body is malformed");
      case NoResourceFoundException ignored ->
          new ApiFailure(404, ErrorCodes.RESOURCE_DOES_NOT_EXIST, "the endpoint does not exist");
      default -> ApiFailure.of(e);
    };
  }

  private static String describe(MethodArgumentNotValidException invalid) {
    return invalid.getBindingResult().getFieldErrors().stream()
        .findFirst()
        .map(error -> error.getField() + " " + error.getDefaultMessage())
        .orElse("request validation failed");
  }

  private static String describe(MethodArgumentTypeMismatchException mistyped) {
    Class<?> wanted = mistyped.getRequiredType();
    boolean numeric = wanted == Integer.class || wanted == Long.class;
    return numeric
        ? mistyped.getName() + " must be a number"
        : mistyped.getName() + " is not a valid value";
  }
}
