package io.opensharing.share;

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
 * Provider HTTP API for share CRUD, behind {@link ProviderTokenDecorator}. Any caller may read
 * shares; only the owner may change them.
 */
@Blocking
@ProducesJson
public final class ShareAdminService {

  private final ShareService shares;

  public ShareAdminService(ShareService shares) {
    this.shares = shares;
  }

  /** {@code POST /shares}: creates a share owned by the caller. Needs CREATE_SHARE. */
  @Post("/shares")
  @StatusCode(201)
  public ShareResponse create(ServiceRequestContext ctx, CreateShareRequest request) {
    return shares.create(ProviderTokenDecorator.user(ctx), request);
  }

  /** {@code GET /shares}: lists every share by name, unpaged. */
  @Get("/shares")
  public ListResponse<ShareResponse> list() {
    return shares.list();
  }

  /** {@code GET /shares/{share}}: gets a share by name in any case. */
  @Get("/shares/{share}")
  public ShareResponse get(@Param String share) {
    return shares.get(share);
  }

  /** {@code PATCH /shares/{share}}: updates the fields set in the body. Owner only. */
  @Patch("/shares/{share}")
  public ShareResponse update(
      ServiceRequestContext ctx, @Param String share, UpdateShareRequest request) {
    return shares.update(ProviderTokenDecorator.user(ctx), share, request);
  }

  /** {@code DELETE /shares/{share}}: deletes the share. Owner only. */
  @Delete("/shares/{share}")
  public HttpResponse delete(ServiceRequestContext ctx, @Param String share) {
    shares.delete(ProviderTokenDecorator.user(ctx), share);
    return HttpResponse.of(HttpStatus.NO_CONTENT);
  }
}
