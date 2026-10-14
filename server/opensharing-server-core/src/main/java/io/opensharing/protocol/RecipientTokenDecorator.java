package io.opensharing.protocol;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.auth.OAuth2Token;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthTokenExtractors;
import io.netty.util.AttributeKey;
import io.opensharing.http.ApiException;
import io.opensharing.recipient.RecipientPrincipal;
import io.opensharing.recipient.RecipientService;

/**
 * Authenticates a sharing protocol request by its recipient bearer token; services read the
 * recipient with {@link #recipient}. Missing, unknown, unactivated and expired tokens get a 401.
 */
public final class RecipientTokenDecorator implements DecoratingHttpServiceFunction {

  private static final AttributeKey<RecipientPrincipal> RECIPIENT =
      AttributeKey.valueOf(RecipientTokenDecorator.class, "recipient");

  private final RecipientService recipients;

  public RecipientTokenDecorator(RecipientService recipients) {
    this.recipients = recipients;
  }

  @Override
  public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req) {
    OAuth2Token token = AuthTokenExtractors.oAuth2().apply(req.headers());
    if (token == null || token.accessToken().isBlank()) {
      throw ApiException.unauthenticated("bearer token is required");
    }
    // The token lookup queries the database, which must not block the event loop.
    return HttpResponse.of(
        () -> {
          try {
            ctx.setAttr(RECIPIENT, recipients.authenticate(token.accessToken()));
            return delegate.serve(ctx, req);
          } catch (Exception e) {
            return HttpResponse.ofFailure(e);
          }
        },
        ctx.blockingTaskExecutor());
  }

  /**
   * Returns the recipient this decorator authenticated for the request. Only services mounted
   * behind this decorator may call it; anywhere else there is no recipient, so it fails with a 401
   * rather than letting the service run anonymously.
   */
  public static RecipientPrincipal recipient(ServiceRequestContext ctx) {
    RecipientPrincipal recipient = ctx.attr(RECIPIENT);
    if (recipient == null) {
      throw ApiException.unauthenticated("this endpoint requires an authenticated recipient");
    }
    return recipient;
  }
}
