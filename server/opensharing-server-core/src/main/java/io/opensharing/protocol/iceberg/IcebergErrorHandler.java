package io.opensharing.protocol.iceberg;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import io.opensharing.http.ApiExceptionHandler;
import io.opensharing.http.ApiFailure;
import io.opensharing.http.ApiJson;
import java.util.concurrent.CompletionException;

/**
 * Turns any failure of the Iceberg REST catalog into Iceberg's error body, which its clients parse
 * to decide what went wrong; a protocol error body would reach them as an unreadable response.
 * Failures are judged as on the protocol's own endpoints, and only spelled differently. Like
 * {@link ApiExceptionHandler}, register it both as the exception handler and as the outermost
 * decorator, so a failed token check gets the same body.
 */
public final class IcebergErrorHandler
    implements ExceptionHandlerFunction, DecoratingHttpServiceFunction {

  private final ApiExceptionHandler protocolErrors = ApiExceptionHandler.forRecipients();

  @Override
  public HttpResponse handleException(
      ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
    Throwable failure = cause instanceof CompletionException ? cause.getCause() : cause;
    if (failure instanceof IcebergException iceberg) {
      return response(iceberg.status(), iceberg.type(), iceberg.getMessage());
    }
    ApiFailure protocol = protocolErrors.failureOf(cause);
    return response(protocol.status(), typeOf(protocol.status()), protocol.message());
  }

  @Override
  public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req) {
    try {
      return delegate.serve(ctx, req).recover(cause -> handleException(ctx, req, cause));
    } catch (Exception e) {
      return handleException(ctx, req, e);
    }
  }

  /** The Iceberg exception a client raises for {@code status} when no more specific one applies. */
  static String typeOf(int status) {
    return switch (status) {
      case 400 -> IcebergException.BAD_REQUEST;
      case 401 -> "NotAuthorizedException";
      case 403 -> "ForbiddenException";
      case 404 -> "NotFoundException";
      case 405, 406 -> "UnsupportedOperationException";
      case 503 -> "ServiceUnavailableException";
      default -> status >= 500 ? "ServiceFailureException" : "RESTException";
    };
  }

  private static HttpResponse response(int status, String type, String message) {
    ResponseHeadersBuilder headers = ResponseHeaders.builder(status);
    if (status == 401) {
      // RFC 9110 requires a 401 to name the expected scheme in WWW-Authenticate.
      headers.add(HttpHeaderNames.WWW_AUTHENTICATE, "Bearer");
    }
    return ApiJson.response(headers.build(), IcebergErrorResponse.of(message, type, status));
  }
}
