package io.opensharing.http;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Renders every failure as the protocol's {@code {errorCode, message}} body. */
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handle(Exception e) {
    ApiFailure failure = ApiFailure.of(e);
    return ResponseEntity.status(failure.status())
        .body(new ErrorResponse(failure.errorCode(), failure.message()));
  }
}
