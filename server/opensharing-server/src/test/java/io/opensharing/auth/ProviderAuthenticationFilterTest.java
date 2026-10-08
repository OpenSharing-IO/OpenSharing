package io.opensharing.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.exception.CatalogAuthorizationException;
import io.opensharing.exception.CatalogException;
import io.opensharing.http.ErrorCodes;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ProviderAuthenticationFilterTest {

  private static final UserContext ALICE = UserContext.fromUserIdAndName("alice-id", "alice");

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void rejectsUnauthorizedTokenWithUnauthenticated() throws Exception {
    MockHttpServletResponse response =
        filter(new CatalogAuthorizationException("invalid bearer token"));

    assertEquals(401, response.getStatus());
    assertEquals("Bearer", response.getHeader("WWW-Authenticate"));
    assertEquals(ErrorCodes.UNAUTHENTICATED, body(response).get("errorCode").asText());
  }

  @Test
  void mapsCatalogFailureToBadGateway() throws Exception {
    MockHttpServletResponse response = filter(new CatalogException("catalog unreachable"));

    assertEquals(502, response.getStatus());
    assertNull(response.getHeader("WWW-Authenticate"));
    JsonNode body = body(response);
    assertEquals(ErrorCodes.CATALOG_ERROR, body.get("errorCode").asText());
    assertEquals("catalog unreachable", body.get("message").asText());
  }

  @Test
  void mapsUnexpectedFailureToInternalError() throws Exception {
    MockHttpServletResponse response = filter(new IllegalStateException("boom"));

    assertEquals(500, response.getStatus());
    assertEquals(ErrorCodes.INTERNAL_ERROR, body(response).get("errorCode").asText());
  }

  @Test
  void rejectsMissingTokenWithoutCallingTheCatalog() throws Exception {
    List<Privilege> privileges = new ArrayList<>();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/shares");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    new ProviderAuthenticationFilter(authorizing(ALICE, privileges), objectMapper)
        .doFilter(request, response, chain);

    assertEquals(401, response.getStatus());
    assertTrue(privileges.isEmpty());
    assertNull(chain.getRequest());
  }

  @Test
  void asksForCreateShareWhenCreatingAShare() throws Exception {
    List<Privilege> privileges = new ArrayList<>();
    MockHttpServletRequest request = authorized("POST", "/api/shares/", privileges);

    assertEquals(Arrays.asList(Privilege.CREATE_SHARE), privileges);
    assertSame(ALICE, request.getAttribute(ProviderAuthenticationFilter.USER_CONTEXT_ATTRIBUTE));
  }

  @Test
  void onlyAuthenticatesOtherShareRequests() throws Exception {
    List<Privilege> privileges = new ArrayList<>();
    authorized("GET", "/api/shares", privileges);
    authorized("PATCH", "/api/shares/sales", privileges);
    authorized("DELETE", "/api/shares/sales", privileges);

    assertEquals(Arrays.asList(null, null, null), privileges);
  }

  private MockHttpServletRequest authorized(String method, String path, List<Privilege> privileges)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.addHeader("Authorization", "Bearer token");
    MockFilterChain chain = new MockFilterChain();
    new ProviderAuthenticationFilter(authorizing(ALICE, privileges), objectMapper)
        .doFilter(request, new MockHttpServletResponse(), chain);
    assertSame(request, chain.getRequest());
    return request;
  }

  private static CatalogConnector authorizing(UserContext user, List<Privilege> privileges) {
    return (CatalogConnector)
        Proxy.newProxyInstance(
            CatalogConnector.class.getClassLoader(),
            new Class<?>[] {CatalogConnector.class},
            (proxy, method, args) -> {
              privileges.add((Privilege) args[1]);
              return user;
            });
  }

  private MockHttpServletResponse filter(RuntimeException failure) throws Exception {
    CatalogConnector catalog =
        (CatalogConnector)
            Proxy.newProxyInstance(
                CatalogConnector.class.getClassLoader(),
                new Class<?>[] {CatalogConnector.class},
                (proxy, method, args) -> {
                  throw failure;
                });
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/shares");
    request.addHeader("Authorization", "Bearer token");
    MockHttpServletResponse response = new MockHttpServletResponse();
    new ProviderAuthenticationFilter(catalog, objectMapper)
        .doFilter(request, response, new MockFilterChain());
    return response;
  }

  private JsonNode body(MockHttpServletResponse response) throws Exception {
    return objectMapper.readTree(response.getContentAsByteArray());
  }
}
