package io.opensharing.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.exception.CatalogAuthorizationException;
import io.opensharing.http.ApiFailure;
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

/**
 * Authenticates a provider request through the configured catalog. The catalog checks the bearer
 * token, and the privilege when the action needs one, then returns the caller. Missing, invalid, or
 * unauthorized tokens are rejected with 401 before the request reaches a controller.
 */
public class ProviderAuthenticationFilter extends OncePerRequestFilter {

  /** Request attribute holding the caller the catalog returned; read by controller arguments. */
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
    } catch (Exception failed) {
      // Filters run outside the controller advice, so map the failure here.
      ApiFailure failure = ApiFailure.of(failed);
      write(response, failure.status(), failure.errorCode(), failure.message());
      return;
    }
    request.setAttribute(USER_CONTEXT_ATTRIBUTE, user);
    chain.doFilter(request, response);
  }

  // Creating a share needs CREATE_SHARE. Other requests only authenticate; ownership of existing
  // shares is checked by the stores.
  private static Privilege privilegeFor(HttpServletRequest request) {
    return "POST".equalsIgnoreCase(request.getMethod())
            && request.getRequestURI().replaceFirst("/$", "").endsWith("/shares")
        ? Privilege.CREATE_SHARE
        : null;
  }

  // RFC 9110 requires a 401 to name the expected scheme in WWW-Authenticate.
  private void reject(HttpServletResponse response, String message) throws IOException {
    response.setHeader("WWW-Authenticate", "Bearer");
    write(response, HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHENTICATED, message);
  }

  private void write(
      HttpServletResponse response, HttpStatus status, String errorCode, String message)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(errorCode, message));
  }
}
