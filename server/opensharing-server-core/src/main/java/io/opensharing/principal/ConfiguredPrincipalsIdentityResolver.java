package io.opensharing.principal;

import io.opensharing.ObjectNames;
import io.opensharing.config.OpenSharingProperties;
import io.opensharing.runtime.ProviderIdentityResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves a provider-admin request's caller against {@code opensharing.admin.principals} — the
 * {@code local} file catalog's whole identity story, since a file has nobody to ask instead. Held
 * in memory only, built once from configuration at startup: there is no catalog here to delegate
 * authentication to and nothing worth a database row for it, only a fixed list an operator already
 * wrote down.
 *
 * <p>A principal's name doubles as its id: {@code local} mode has no catalog-assigned identity to
 * use instead, and a configured name is already a stable, operator-chosen one.
 */
public final class ConfiguredPrincipalsIdentityResolver implements ProviderIdentityResolver {

  private static final Logger log =
      LoggerFactory.getLogger(ConfiguredPrincipalsIdentityResolver.class);
  private static final String BEARER_PREFIX = "Bearer ";

  private final Map<String, String> namesByToken;

  public ConfiguredPrincipalsIdentityResolver(
      List<OpenSharingProperties.Admin.Principal> principals) {
    if (principals.isEmpty()) {
      log.warn("No opensharing.admin.principals configured; no provider principal can log in");
    }
    Map<String, String> byToken = new LinkedHashMap<>();
    Set<String> seen = new HashSet<>();
    for (OpenSharingProperties.Admin.Principal principal : principals) {
      String name = ObjectNames.validatePrincipalName(principal.getName().trim());
      if (!seen.add(name.toLowerCase(java.util.Locale.ROOT))) {
        throw new IllegalStateException(
            "opensharing.admin.principals lists '" + name + "' more than once");
      }
      String token = principal.getBearerToken();
      if (token == null || token.isBlank()) {
        throw new IllegalStateException(
            "opensharing.admin.principals entry '" + name + "' has no bearer-token configured");
      }
      byToken.put(token, name);
      log.info("Configured provider principal '{}'", name);
    }
    this.namesByToken = Map.copyOf(byToken);
  }

  @Override
  public Optional<Caller> resolve(HttpServletRequest request) {
    String token = bearerToken(request);
    if (token == null) {
      return Optional.empty();
    }
    String name = namesByToken.get(token);
    return name == null ? Optional.empty() : Optional.of(new Caller(name, name, token));
  }

  private static String bearerToken(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    return header != null && header.startsWith(BEARER_PREFIX)
        ? header.substring(BEARER_PREFIX.length()).trim()
        : null;
  }
}
