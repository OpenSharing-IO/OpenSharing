package io.opensharing.recipient;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.StatusCode;
import io.opensharing.auth.ProviderTokenDecorator;
import io.opensharing.http.ListResponse;

/**
 * Provider HTTP API for recipient CRUD, behind {@link ProviderTokenDecorator}. Only the owner may
 * change a recipient. Only the owner and callers with USE_RECIPIENT, who may read anyone's
 * recipients, are meant to list and get recipients.
 */
@Blocking
@ProducesJson
public final class RecipientAdminService {

  private final RecipientService recipients;
  private final String activationBaseUrl;

  /**
   * @param activationBaseUrl the absolute URL recipient activation links are built under, on the
   *     address recipients reach the host at
   */
  public RecipientAdminService(RecipientService recipients, String activationBaseUrl) {
    this.recipients = recipients;
    this.activationBaseUrl = activationBaseUrl;
  }

  /**
   * {@code POST /recipients}: creates a recipient owned by the caller, with a new activation code.
   * Needs CREATE_RECIPIENT.
   */
  @Post("/recipients")
  @StatusCode(201)
  public RecipientResponse create(ServiceRequestContext ctx, CreateRecipientRequest request) {
    return recipients.create(ProviderTokenDecorator.user(ctx), request, activationBaseUrl);
  }

  /** {@code GET /recipients}: lists every recipient by name, unpaged. */
  @Get("/recipients")
  public ListResponse<RecipientResponse> list() {
    return recipients.list(activationBaseUrl);
  }

  /** {@code GET /recipients/{recipient}}: gets a recipient by name in any case. */
  @Get("/recipients/{recipient}")
  public RecipientResponse get(@Param String recipient) {
    return recipients.get(recipient, activationBaseUrl);
  }

  /** {@code PATCH /recipients/{recipient}}: updates the fields set in the body. Owner only. */
  @Patch("/recipients/{recipient}")
  public RecipientResponse update(
      ServiceRequestContext ctx, @Param String recipient, UpdateRecipientRequest request) {
    return recipients.update(
        ProviderTokenDecorator.user(ctx), recipient, request, activationBaseUrl);
  }

  /** {@code DELETE /recipients/{recipient}}: deletes the recipient and its tokens. Owner only. */
  @Delete("/recipients/{recipient}")
  public HttpResponse delete(ServiceRequestContext ctx, @Param String recipient) {
    recipients.delete(ProviderTokenDecorator.user(ctx), recipient);
    return HttpResponse.of(HttpStatus.NO_CONTENT);
  }

  /**
   * {@code POST /recipients/{recipient}/rotate-token}: issues a replacement token with a new
   * activation URL. Existing tokens stop working after the grace window, immediately unless the
   * request sets one. Owner only.
   */
  @Post("/recipients/{recipient}/rotate-token")
  @StatusCode(201)
  public IssuedTokenResponse rotateToken(
      ServiceRequestContext ctx, @Param String recipient, RotateTokenRequest request) {
    return recipients.rotateToken(
        ProviderTokenDecorator.user(ctx), recipient, request, activationBaseUrl);
  }
}
