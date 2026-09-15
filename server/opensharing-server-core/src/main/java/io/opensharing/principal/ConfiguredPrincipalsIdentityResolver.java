package io.opensharing.principal;

import io.opensharing.ObjectNames;
import io.opensharing.auth.BearerTokens;
import io.opensharing.config.OpenSharingProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves provider-admin callers against {@code opensharing.admin.principals}. Used by the local
 * catalog, which has no {@code authorize} implementation to ask instead.
 */
public final class ConfiguredPrincipalsIdentityResolver implements ProviderIdentityResolver {

  private static final Logger log =
      LoggerFactory.getLogger(ConfiguredPrincipalsIdentityResolver.class);

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
      if (!seen.add(name.toLowerCase(Locale.ROOT))) {
        throw new IllegalStateException(
            "opensharing.admin.principals lists '" + name + "' more than once");
      }
      String token = principal.getBearerToken();
      if (token == null || token.isBlank()) {
        throw new IllegalStateException(
            "opensharing.admin.principals entry '" + name + "' has no bearer-token configured");
      }
      byToken.put(token, name);
    }
    this.namesByToken = Map.copyOf(byToken);
  }

  @Override
  public Optional<Caller> resolve(HttpServletRequest request) {
    return BearerTokens.from(request)
        .flatMap(
            token -> {
              String name = namesByToken.get(token);
              return name == null
                  ? Optional.empty()
                  : Optional.of(new Caller(name, name, token));
            });
  }
}
