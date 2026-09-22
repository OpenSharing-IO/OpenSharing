package io.opensharing.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.exception.CatalogAuthorizationException;
import io.opensharing.http.ErrorCodes;
import io.opensharing.http.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates a provider request through the configured catalog. */
public class ProviderAuthenticationFilter extends OncePerRequestFilter {

  public static final String USER_CONTEXT_ATTRIBUTE = "io.opensharing.userContext";

  private final CatalogConnector catalog;
  private final ObjectMapper objectMapper;

  public ProviderAuthenticationFilter(CatalogConnector catalog, ObjectMapper objectMapper) {
    this.catalog = catalog;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String token = BearerTokens.from(request).orElse(null);
    if (token == null) {
      reject(response, "bearer token is required");
      return;
    }
    UserContext user;
    try {
      user =
          catalog.authorize(
              new AuthContext(null, new UserContext(null, token, null)), privilegeFor(request));
    } catch (CatalogAuthorizationException rejected) {
      reject(response, "bearer token is invalid or unauthorized");
      return;
    }
    request.setAttribute(USER_CONTEXT_ATTRIBUTE, user);
    chain.doFilter(request, response);
  }

  private static String privilegeFor(HttpServletRequest request) {
    return "POST".equalsIgnoreCase(request.getMethod())
            && request.getRequestURI().replaceFirst("/$", "").endsWith("/shares")
        ? "CREATE_SHARE"
        : null;
  }

  private void reject(HttpServletResponse response, String message) throws IOException {
    response.setHeader("WWW-Authenticate", "Bearer");
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(
        response.getOutputStream(), new ErrorResponse(ErrorCodes.UNAUTHENTICATED, message));
  }
}
