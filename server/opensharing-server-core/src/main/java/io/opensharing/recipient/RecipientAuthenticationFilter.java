package io.opensharing.recipient;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opensharing.auth.BearerTokens;
import io.opensharing.auth.TokenHashes;
import io.opensharing.http.ErrorCodes;
import io.opensharing.http.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates protocol requests with an activated, unexpired recipient bearer token. */
public class RecipientAuthenticationFilter extends OncePerRequestFilter {

  private final RecipientStore recipients;
  private final ObjectMapper objectMapper;
  private final List<String> excludedPrefixes;

  public RecipientAuthenticationFilter(
      RecipientStore recipients, ObjectMapper objectMapper, List<String> excludedPrefixes) {
    this.recipients = recipients;
    this.objectMapper = objectMapper;
    this.excludedPrefixes = excludedPrefixes;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (excludedPrefixes.stream().anyMatch(prefix -> isWithin(request.getRequestURI(), prefix))) {
      chain.doFilter(request, response);
      return;
    }

    String bearer = BearerTokens.from(request).orElse(null);
    if (bearer == null) {
      reject(response, "bearer token is required");
      return;
    }
    RecipientTokenEntity token =
        recipients.findUsableToken(TokenHashes.sha256(bearer), Instant.now()).orElse(null);
    if (token == null) {
      reject(response, "bearer token is invalid or expired");
      return;
    }
    request.setAttribute(
        RecipientPrincipal.REQUEST_ATTRIBUTE, RecipientPrincipal.from(token.getRecipient()));
    chain.doFilter(request, response);
  }

  private static boolean isWithin(String path, String prefix) {
    return prefix != null
        && !prefix.isBlank()
        && (path.equals(prefix) || path.startsWith(prefix + "/"));
  }

  private void reject(HttpServletResponse response, String message) throws IOException {
    response.setHeader("WWW-Authenticate", "Bearer");
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(
        response.getOutputStream(), new ErrorResponse(ErrorCodes.UNAUTHENTICATED, message));
  }
}
