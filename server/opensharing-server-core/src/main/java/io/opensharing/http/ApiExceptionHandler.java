package io.opensharing.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import java.util.Locale;
import java.util.concurrent.CompletionException;

/**
 * Turns any failure into the protocol's {@code {errorCode, message}} JSON body. Armeria hands it
 * failures two ways, so register it twice: as the annotated service's exception handler, for what
 * the service methods throw, and as the outermost decorator, for what other decorators such as a
 * token check throw, which the exception handler never sees.
 */
public final class ApiExceptionHandler
    implements ExceptionHandlerFunction, DecoratingHttpServiceFunction {

  @Override
  public HttpResponse handleException(
      ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
    return response(failureOf(cause));
  }

  @Override
  public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req) {
    try {
      return delegate.serve(ctx, req).recover(cause -> handleException(ctx, req, cause));
    } catch (Exception e) {
      return handleException(ctx, req, e);
    }
  }

  /** Maps Armeria's own failures; everything else is mapped by {@link ApiFailure}. */
  static ApiFailure failureOf(Throwable cause) {
    Throwable failure = cause instanceof CompletionException ? cause.getCause() : cause;
    return switch (failure) {
      case HttpStatusException status when status.httpStatus() == HttpStatus.NOT_FOUND ->
          new ApiFailure(404, ErrorCodes.RESOURCE_DOES_NOT_EXIST, "the endpoint does not exist");
      case HttpStatusException status -> statusFailure(status.httpStatus());
      // Armeria reports an unreadable body as an IllegalArgumentException around Jackson's failure.
      case IllegalArgumentException illegal
          when illegal.getCause() instanceof JsonProcessingException json ->
          ApiFailure.of(json);
      case Exception e -> ApiFailure.of(e);
      default -> ApiFailure.of(new RuntimeException(failure));
    };
  }

  private static ApiFailure statusFailure(HttpStatus status) {
    String message = status.reasonPhrase().toLowerCase(Locale.ROOT);
    return status.isServerError()
        ? new ApiFailure(status.code(), ErrorCodes.INTERNAL_ERROR, message)
        : new ApiFailure(status.code(), ErrorCodes.MALFORMED_REQUEST, message);
  }

  private static HttpResponse response(ApiFailure failure) {
    ResponseHeadersBuilder headers = ResponseHeaders.builder(failure.status());
    if (failure.status() == 401) {
      // RFC 9110 requires a 401 to name the expected scheme in WWW-Authenticate.
      headers.add(HttpHeaderNames.WWW_AUTHENTICATE, "Bearer");
    }
    return ApiJson.response(
        headers.build(), new ErrorResponse(failure.errorCode(), failure.message()));
  }
}
