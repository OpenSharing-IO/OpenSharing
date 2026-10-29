package io.opensharing.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.exception.CatalogAuthenticationException;
import io.opensharing.exception.CatalogException;
import io.opensharing.http.ApiExceptionHandler;
import io.opensharing.util.ErrorCodes;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ProviderTokenDecoratorTest {

  private static final UserContext ALICE = UserContext.fromUserIdAndName("alice-id", "alice");

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void rejectsTokenTheCatalogCannotAuthenticate() throws Exception {
    AggregatedHttpResponse response =
        serve(failing(new CatalogAuthenticationException("invalid bearer token")), "token");

    assertEquals(401, response.status().code());
    assertEquals("Bearer", response.headers().get("WWW-Authenticate"));
    assertEquals(ErrorCodes.UNAUTHENTICATED, body(response).get("errorCode").asText());
  }

  @Test
  void mapsCatalogFailureToBadGateway() throws Exception {
    AggregatedHttpResponse response =
        serve(failing(new CatalogException("catalog unreachable")), "token");

    assertEquals(502, response.status().code());
    assertNull(response.headers().get("WWW-Authenticate"));
    JsonNode body = body(response);
    assertEquals(ErrorCodes.CATALOG_ERROR, body.get("errorCode").asText());
    assertEquals("catalog unreachable", body.get("message").asText());
  }

  @Test
  void mapsUnexpectedFailureToInternalError() throws Exception {
    AggregatedHttpResponse response = serve(failing(new IllegalStateException("boom")), "token");

    assertEquals(500, response.status().code());
    assertEquals(ErrorCodes.INTERNAL_ERROR, body(response).get("errorCode").asText());
  }

  @Test
  void rejectsMissingTokenWithoutCallingTheCatalog() {
    List<Privilege> privileges = new ArrayList<>();

    AggregatedHttpResponse response = serve(authorizing(ALICE, privileges), null);

    assertEquals(401, response.status().code());
    assertTrue(privileges.isEmpty());
  }

  @Test
  void onlyAuthenticatesAndHandsTheCallerToTheService() {
    List<Privilege> privileges = new ArrayList<>();
    AtomicReference<UserContext> served = new AtomicReference<>();
    HttpService service =
        (ctx, req) -> {
          served.set(ProviderTokenDecorator.user(ctx));
          return HttpResponse.of(HttpStatus.NO_CONTENT);
        };

    AggregatedHttpResponse response =
        serve(service, authorizing(ALICE, privileges), HttpMethod.POST, "token");

    assertEquals(204, response.status().code());
    assertSame(ALICE, served.get());
    assertEquals(Arrays.asList((Privilege) null), privileges);
  }

  private static AggregatedHttpResponse serve(CatalogConnector catalog, String token) {
    return serve((ctx, req) -> HttpResponse.of(HttpStatus.OK), catalog, HttpMethod.GET, token);
  }

  private static AggregatedHttpResponse serve(
      HttpService service, CatalogConnector catalog, HttpMethod method, String token) {
    RequestHeaders headers =
        token == null
            ? RequestHeaders.of(method, "/shares")
            : RequestHeaders.of(method, "/shares", "authorization", "Bearer " + token);
    HttpRequest request = HttpRequest.of(headers);
    HttpService decorated =
        service
            .decorate(new ProviderTokenDecorator(catalog))
            .decorate(new ApiExceptionHandler());
    try {
      return decorated.serve(ServiceRequestContext.of(request), request).aggregate().join();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static CatalogConnector authorizing(UserContext user, List<Privilege> privileges) {
    return catalog(
        args -> {
          privileges.add((Privilege) args[1]);
          return user;
        });
  }

  private static CatalogConnector failing(RuntimeException failure) {
    return catalog(
        args -> {
          throw failure;
        });
  }

  private static CatalogConnector catalog(Function<Object[], Object> authorize) {
    return (CatalogConnector)
        Proxy.newProxyInstance(
            CatalogConnector.class.getClassLoader(),
            new Class<?>[] {CatalogConnector.class},
            (proxy, method, args) -> authorize.apply(args));
  }

  private JsonNode body(AggregatedHttpResponse response) throws Exception {
    return objectMapper.readTree(response.contentUtf8());
  }
}
