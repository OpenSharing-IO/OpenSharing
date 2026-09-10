package io.opensharing.serving;

import io.opensharing.http.ApiFailure;
import io.opensharing.protocol.IcebergError;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders a failure of the Iceberg REST catalog in Iceberg's own error body rather than the
 * protocol's.
 *
 * <p>The status alone is not enough for an Iceberg client: it reads the body to tell a table that
 * does not exist from a server it cannot understand, and a 404 it cannot parse becomes a transport
 * error instead of the plain "no such table" a query planner is asking for. The judgement about the
 * failure is the same one every other endpoint makes — only the spelling is Iceberg's.
 */
@RestControllerAdvice(assignableTypes = IcebergCatalogController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IcebergErrorHandler {

  @ExceptionHandler(Exception.class)
  public ResponseEntity<IcebergError> handle(Exception e) {
    ApiFailure failure = ApiFailure.of(e);
    return ResponseEntity.status(failure.status())
        .body(
            IcebergError.of(
                failure.message(), failure.errorCode(), failure.status().value()));
  }
}
