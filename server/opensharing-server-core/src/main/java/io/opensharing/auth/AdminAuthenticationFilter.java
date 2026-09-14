package io.opensharing.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opensharing.http.ErrorCodes;
import io.opensharing.http.ErrorResponse;
import io.opensharing.principal.Caller;
import io.opensharing.runtime.ProviderIdentityResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a provider-admin request as the {@link Caller} its {@link ProviderIdentityResolver}
 * resolves it to.
 *
 * <p>There is exactly one resolver in any deployment — the catalog itself for {@code unity} (a fresh
 * call to its own authorize endpoint on every request, embedded or standalone, over a loopback or a
 * remote address), or a fixed configured list for {@code local}, which has no catalog to delegate to
 * — never both and never neither. This filter holds no identity store of its own: nothing here is
 * provisioned, cached, or written to a database, so there is nothing about a caller left behind once
 * their request is over.
 */
public class AdminAuthenticationFilter extends OncePerRequestFilter {

  private final ProviderIdentityResolver identityResolver;
  private final ObjectMapper objectMapper;

  public AdminAuthenticationFilter(
      ProviderIdentityResolver identityResolver, ObjectMapper objectMapper) {
    this.identityResolver = identityResolver;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<Caller> resolved = identityResolver.resolve(request);
    if (resolved.isEmpty()) {
      reject(response, "a provider-admin bearer token naming a known, authorized principal is required");
      return;
    }
    request.setAttribute(Caller.REQUEST_ATTRIBUTE, resolved.get());
    chain.doFilter(request, response);
  }

  private void reject(HttpServletResponse response, String message) throws IOException {
    response.setHeader("WWW-Authenticate", "Bearer");
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(
        response.getOutputStream(), new ErrorResponse(ErrorCodes.UNAUTHENTICATED, message));
  }
}
