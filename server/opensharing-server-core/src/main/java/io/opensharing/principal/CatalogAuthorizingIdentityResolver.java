package io.opensharing.principal;

import io.opensharing.catalog.CatalogAuthorizationException;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.runtime.ProviderIdentityResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * Resolves a provider-admin request's caller by asking the {@link CatalogConnector} itself whose
 * bearer token this is — see {@link CatalogConnector#authorize} — instead of maintaining a parallel
 * {@code opensharing.admin.principals} list or storing a copy of anyone's credential. Works with
 * whichever connector this server is configured with; today that is Unity Catalog, the only
 * connector that implements {@link CatalogConnector#authorize}, but nothing here is specific to it.
 * One resolver serves both standalone mode (a connector pointed at a remote catalog) and embedded
 * mode (pointed at the host's own loopback address): authorizing a bearer token is a general
 * connector capability either way, not something embedding adds.
 *
 * <p>Create and Create Recipient are the only two provider-admin operations the catalog is asked to
 * gate ({@code CREATE_SHARE}, {@code CREATE_RECIPIENT} — see {@link #privilegeFor}); every other
 * request resolves identity only, and this server enforces its own ownership rules on top (a share
 * or recipient may only be changed by the principal that owns it) rather than asking the catalog to
 * decide that again on every call.
 */
public final class CatalogAuthorizingIdentityResolver implements ProviderIdentityResolver {

  private static final String BEARER_PREFIX = "Bearer ";

  private final CatalogConnector connector;

  public CatalogAuthorizingIdentityResolver(CatalogConnector connector) {
    this.connector = connector;
  }

  @Override
  public Optional<Caller> resolve(HttpServletRequest request) {
    String token = bearerToken(request);
    if (token == null) {
      return Optional.empty();
    }
    try {
      var principal = connector.authorize(token, privilegeFor(request));
      return Optional.of(new Caller(principal.id(), principal.name(), token));
    } catch (CatalogAuthorizationException notAuthorized) {
      return Optional.empty();
    }
  }

  /**
   * The one privilege this server ever asks the catalog to check on a provider-admin request — the
   * two operations that create a new, otherwise-unowned object, matched on method and path since
   * that is all a filter this far upstream has to go on.
   */
  private static String privilegeFor(HttpServletRequest request) {
    if (!"POST".equalsIgnoreCase(request.getMethod())) {
      return null;
    }
    String path = request.getRequestURI();
    if (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    if (path.endsWith("/shares")) {
      return "CREATE_SHARE";
    }
    if (path.endsWith("/recipients")) {
      return "CREATE_RECIPIENT";
    }
    return null;
  }

  private static String bearerToken(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    return header != null && header.startsWith(BEARER_PREFIX)
        ? header.substring(BEARER_PREFIX.length()).trim()
        : null;
  }
}
