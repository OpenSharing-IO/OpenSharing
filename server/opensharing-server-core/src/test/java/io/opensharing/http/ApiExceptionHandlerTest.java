package io.opensharing.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.opensharing.catalog.AssetType;
import io.opensharing.util.ErrorCodes;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class ApiExceptionHandlerTest {

  private final ApiExceptionHandler handler = new ApiExceptionHandler();

  @Test
  void mapsUnroutedRequestsAsNotFound() {
    assertEquals(
        new ApiFailure(404, ErrorCodes.RESOURCE_DOES_NOT_EXIST, "the endpoint does not exist"),
        ApiExceptionHandler.failureOf(HttpStatusException.of(HttpStatus.NOT_FOUND)));
  }

  @Test
  void mapsOtherArmeriaStatusesByTheirReason() {
    assertEquals(
        new ApiFailure(415, ErrorCodes.MALFORMED_REQUEST, "unsupported media type"),
        ApiExceptionHandler.failureOf(
            HttpStatusException.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE)));
    assertEquals(
        new ApiFailure(503, ErrorCodes.INTERNAL_ERROR, "service unavailable"),
        ApiExceptionHandler.failureOf(
            HttpStatusException.of(HttpStatus.SERVICE_UNAVAILABLE)));
  }

  @Test
  void mapsUnreadableBodiesThroughTheirJsonCause() {
    InvalidFormatException invalid =
        assertThrows(
            InvalidFormatException.class,
            () -> new ObjectMapper().readValue("{\"type\":\"VIEW\"}", Body.class));
    assertEquals(
        new ApiFailure(
            400, ErrorCodes.INVALID_PARAMETER_VALUE, "type must be one of [TABLE, SCHEMA]"),
        ApiExceptionHandler.failureOf(new IllegalArgumentException(invalid)));
  }

  @Test
  void mapsInvalidParametersByTheirMessage() {
    assertEquals(
        new ApiFailure(400, ErrorCodes.INVALID_PARAMETER_VALUE, "maxResults is not a number"),
        ApiExceptionHandler.failureOf(
            new IllegalArgumentException("maxResults is not a number")));
  }

  @Test
  void leavesOtherFailuresToApiFailure() {
    assertEquals(
        new ApiFailure(404, ErrorCodes.RESOURCE_DOES_NOT_EXIST, "no share"),
        ApiExceptionHandler.failureOf(
            new CompletionException(ApiException.notFound("no share"))));
    assertEquals(
        new ApiFailure(500, ErrorCodes.INTERNAL_ERROR, "internal server error"),
        ApiExceptionHandler.failureOf(new AssertionError("boom")));
  }

  @Test
  void writesTheProtocolErrorBody() throws Exception {
    AggregatedHttpResponse response = handle(ApiException.permissionDenied("not the owner"));

    assertEquals(403, response.status().code());
    assertEquals(MediaType.JSON_UTF_8, response.contentType());
    assertNull(response.headers().get(HttpHeaderNames.WWW_AUTHENTICATE));
    JsonNode body = new ObjectMapper().readTree(response.contentUtf8());
    assertEquals(ErrorCodes.PERMISSION_DENIED, body.get("errorCode").asText());
    assertEquals("not the owner", body.get("message").asText());
  }

  @Test
  void namesTheBearerSchemeOnUnauthenticated() {
    AggregatedHttpResponse response = handle(ApiException.unauthenticated("token is missing"));

    assertEquals(401, response.status().code());
    assertEquals("Bearer", response.headers().get(HttpHeaderNames.WWW_AUTHENTICATE));
  }

  @Test
  void decoratesWhatAServiceThrowsAndWhatItsResponseFailsWith() throws Exception {
    HttpService throwing =
        (ctx, req) -> {
          throw ApiException.notFound("thrown");
        };
    HttpService failing = (ctx, req) -> HttpResponse.ofFailure(ApiException.notFound("failed"));

    assertEquals(404, serve(throwing.decorate(handler)).status().code());
    assertEquals(404, serve(failing.decorate(handler)).status().code());
  }

  private AggregatedHttpResponse handle(Throwable cause) {
    HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
    return handler.handleException(ServiceRequestContext.of(req), req, cause).aggregate().join();
  }

  private static AggregatedHttpResponse serve(HttpService service) throws Exception {
    HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
    return service.serve(ServiceRequestContext.of(req), req).aggregate().join();
  }

  record Body(AssetType type) {}
}
