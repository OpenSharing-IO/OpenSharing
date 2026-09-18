package io.opensharing.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/** Extracts RFC 6750 bearer tokens from requests. */
public final class BearerTokens {

  private static final String SCHEME = "Bearer ";

  private BearerTokens() {}

  public static Optional<String> from(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (header == null || header.length() <= SCHEME.length()) {
      return Optional.empty();
    }
    if (!header.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
      return Optional.empty();
    }
    String token = header.substring(SCHEME.length()).trim();
    return token.isEmpty() ? Optional.empty() : Optional.of(token);
  }
}
