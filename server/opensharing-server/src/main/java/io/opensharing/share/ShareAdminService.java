package io.opensharing.share;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.annotation.Default;
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
 * Provider HTTP API for share CRUD and recipient grants, behind {@link ProviderTokenDecorator}.
 * Only the owner may change a share. The owner and callers with USE_SHARE may read it; listing
 * without USE_SHARE returns only the caller's own shares.
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

  /**
   * {@code GET /shares}: lists one page of shares by name: every share with USE_SHARE, otherwise
   * the caller's own.
   */
  @Get("/shares")
  public ListResponse<ShareResponse> list(
      ServiceRequestContext ctx,
      @Param @Nullable Integer maxResults,
      @Param @Nullable String pageToken) {
    return shares.list(ProviderTokenDecorator.user(ctx), maxResults, pageToken);
  }

  /**
   * {@code GET /shares/{share}}: gets a share by name in any case, with the objects in it when
   * {@code include_shared_data} is true. Owner or USE_SHARE.
   */
  @Get("/shares/{share}")
  public ShareResponse get(
      ServiceRequestContext ctx,
      @Param String share,
      @Param("include_shared_data") @Default("false") boolean includeSharedData) {
    return shares.get(ProviderTokenDecorator.user(ctx), share, includeSharedData);
  }

  /**
   * {@code PATCH /shares/{share}}: applies the object removes, then the adds, in {@code updates},
   * then the other fields set in the body, all or nothing. Owner only.
   */
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

  /**
   * {@code GET /shares/{share}/permissions}: lists one page of who holds which privilege on the
   * share. Owner or USE_SHARE.
   */
  @Get("/shares/{share}/permissions")
  public ListResponse<SharePermissionResponse> listPermissions(
      ServiceRequestContext ctx,
      @Param String share,
      @Param @Nullable Integer maxResults,
      @Param @Nullable String pageToken) {
    return shares.listPermissions(ProviderTokenDecorator.user(ctx), share, maxResults, pageToken);
  }

  /**
   * {@code PATCH /shares/{share}/permissions}: grants and revokes privileges in order, all or
   * nothing, then returns the first page of the share's permissions. Owner only.
   */
  @Patch("/shares/{share}/permissions")
  public ListResponse<SharePermissionResponse> updatePermissions(
      ServiceRequestContext ctx, @Param String share, UpdateSharePermissionsRequest request) {
    return shares.updatePermissions(ProviderTokenDecorator.user(ctx), share, request);
  }
}
