package io.opensharing.recipient;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opensharing.auth.BearerTokens;
import io.opensharing.http.ErrorCodes;
import io.opensharing.http.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates protocol requests as a recipient using its bearer token, and holds the request to the
 * recipient's IP access list.
 *
 * <p>{@code excludedPathPrefixes} matters because the provider and activation APIs may be mounted
 * under the protocol prefix itself (e.g. {@code protocol-prefix} of {@code /api/2.1/opensharing}
 * with {@code provider.base-path} of {@code /api/2.1/opensharing/provider}), which is registered
 * as a servlet URL pattern of its own but is <em>also</em> a match for this filter's broader one.
 * Without the exclusion, this filter would run on every provider and activation request too, and
 * reject them for lacking a recipient token before the filter that actually knows how to
 * authenticate them — or, for activation, any filter at all — gets a chance to.
 */
public class RecipientAuthenticationFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RecipientAuthenticationFilter.class);

  private final RecipientTokenService tokenService;
  private final ObjectMapper objectMapper;
  private final List<String> excludedPathPrefixes;

  public RecipientAuthenticationFilter(
      RecipientTokenService tokenService,
      ObjectMapper objectMapper,
      List<String> excludedPathPrefixes) {
    this.tokenService = tokenService;
    this.objectMapper = objectMapper;
    this.excludedPathPrefixes = excludedPathPrefixes;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    for (String excluded : excludedPathPrefixes) {
      if (!excluded.isBlank() && path.startsWith(excluded)) {
        chain.doFilter(request, response);
        return;
      }
    }
    Optional<String> bearerToken = BearerTokens.from(request);
    if (bearerToken.isEmpty()) {
      reject(response, "a bearer token is required");
      return;
    }
    Optional<RecipientTokenEntity> token = tokenService.findUsableToken(bearerToken.get());
    if (token.isEmpty()) {
      reject(response, "the bearer token is invalid, expired or revoked");
      return;
    }
    RecipientEntity recipient = token.get().getRecipient();
    if (!IpAccessList.allows(recipient.getIpAccessList(), request.getRemoteAddr())) {
      log.warn(
          "Rejected a request for recipient '{}' from {}, which its IP access list does not allow",
          recipient.getName(),
          request.getRemoteAddr());
      forbid(response);
      return;
    }
    request.setAttribute(
        RecipientPrincipal.REQUEST_ATTRIBUTE, RecipientPrincipal.of(recipient));
    chain.doFilter(request, response);
  }

  private void reject(HttpServletResponse response, String message) throws IOException {
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setHeader("WWW-Authenticate", "Bearer");
    write(response, ErrorCodes.UNAUTHENTICATED, message);
  }

  /** The address is deliberately not echoed back to the caller. */
  private void forbid(HttpServletResponse response) throws IOException {
    response.setStatus(HttpStatus.FORBIDDEN.value());
    write(
        response,
        ErrorCodes.PERMISSION_DENIED,
        "this recipient may not be used from this network address");
  }

  private void write(HttpServletResponse response, String errorCode, String message)
      throws IOException {
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(errorCode, message));
  }
}
