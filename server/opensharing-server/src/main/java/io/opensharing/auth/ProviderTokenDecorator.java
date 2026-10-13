package io.opensharing.auth;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.auth.OAuth2Token;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthTokenExtractors;
import io.netty.util.AttributeKey;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.http.ApiException;

/**
 * Authenticates a provider request through the catalog, which checks the bearer token and returns
 * the caller; services read it with {@link #user}. Missing or rejected tokens get a 401.
 */
public final class ProviderTokenDecorator implements DecoratingHttpServiceFunction {

  private static final AttributeKey<UserContext> USER =
      AttributeKey.valueOf(ProviderTokenDecorator.class, "user");

  private final CatalogConnector catalog;

  public ProviderTokenDecorator(CatalogConnector catalog) {
    this.catalog = catalog;
  }

  @Override
  public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req) {
    OAuth2Token token = AuthTokenExtractors.oAuth2().apply(req.headers());
    if (token == null || token.accessToken().isBlank()) {
      throw ApiException.unauthenticated("bearer token is required");
    }
    // A catalog may call out to a remote service, which must not block the event loop.
    return HttpResponse.of(
        () -> {
          try {
            ctx.setAttr(USER, authenticate(token.accessToken()));
            return delegate.serve(ctx, req);
          } catch (Exception e) {
            return HttpResponse.ofFailure(e);
          }
        },
        ctx.blockingTaskExecutor());
  }

  /**
   * Returns the caller this decorator authenticated for the request. Only services mounted behind
   * this decorator may call it; anywhere else there is no caller, so it fails with a 401 rather
   * than letting the service run anonymously.
   */
  public static UserContext user(ServiceRequestContext ctx) {
    UserContext user = ctx.attr(USER);
    if (user == null) {
      throw ApiException.unauthenticated("this endpoint requires an authenticated user");
    }
    return user;
  }

  // Asks the catalog who owns the token, with no privilege so that only the token is checked. The
  // catalog's userId becomes the caller's identity, such as the owner of the shares they create.
  private UserContext authenticate(String token) {
    return catalog.authorize(new AuthContext(null, new UserContext(null, token, null)), null);
  }
}
