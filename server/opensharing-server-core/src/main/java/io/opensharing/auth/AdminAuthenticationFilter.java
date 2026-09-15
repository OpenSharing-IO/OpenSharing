package io.opensharing.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.CatalogPrincipal;
import io.opensharing.exception.CatalogAuthorizationException;
import io.opensharing.http.ErrorCodes;
import io.opensharing.http.ErrorResponse;
import io.opensharing.principal.Caller;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates a provider-admin request through the configured catalog. */
public class AdminAuthenticationFilter extends OncePerRequestFilter {

  private final CatalogConnector catalog;
  private final ObjectMapper objectMapper;

  public AdminAuthenticationFilter(CatalogConnector catalog, ObjectMapper objectMapper) {
    this.catalog = catalog;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String token = BearerTokens.from(request).orElse(null);
    if (token == null) {
      reject(response);
      return;
    }
    CatalogPrincipal principal;
    try {
      principal = catalog.authorize(token, privilegeFor(request));
    } catch (CatalogAuthorizationException rejected) {
      reject(response);
      return;
    }
    request.setAttribute(
        Caller.REQUEST_ATTRIBUTE, new Caller(principal.id(), principal.name(), token));
    chain.doFilter(request, response);
  }

  private static String privilegeFor(HttpServletRequest request) {
    return "POST".equalsIgnoreCase(request.getMethod())
            && request.getRequestURI().replaceFirst("/$", "").endsWith("/shares")
        ? "CREATE_SHARE"
        : null;
  }

  private void reject(HttpServletResponse response) throws IOException {
    response.setHeader("WWW-Authenticate", "Bearer");
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(
        response.getOutputStream(),
        new ErrorResponse(
            ErrorCodes.UNAUTHENTICATED,
            "a provider-admin bearer token naming a known, authorized principal is required"));
  }
}
