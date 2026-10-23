package io.opensharing.protocol;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.ProducesJson;
import io.opensharing.asset.schema.ProtocolSchemas;
import io.opensharing.asset.schema.SchemaResponse;
import io.opensharing.asset.share.GetShareResponse;
import io.opensharing.asset.share.ProtocolShares;
import io.opensharing.http.ListResponse;
import io.opensharing.share.ShareResponse;

/**
 * The Delta Sharing protocol API: every endpoint recipients call with their bearer token, served
 * behind {@link RecipientTokenDecorator}.
 */
@Blocking
@ProducesJson
public final class OpenSharingProtocolService {

  private final ProtocolShares shares;
  private final ProtocolSchemas schemas;

  public OpenSharingProtocolService(ProtocolShares shares, ProtocolSchemas schemas) {
    this.shares = shares;
    this.schemas = schemas;
  }

  /** {@code GET /shares}: lists one page of the shares the recipient holds SELECT on. */
  @Get("/shares")
  public ListResponse<ShareResponse> listShares(
      ServiceRequestContext ctx,
      @Param @Nullable Integer maxResults,
      @Param @Nullable String pageToken) {
    return shares.list(RecipientTokenDecorator.recipient(ctx), maxResults, pageToken);
  }

  /** {@code GET /shares/{share}}: gets a share the recipient holds SELECT on. */
  @Get("/shares/{share}")
  public GetShareResponse getShare(ServiceRequestContext ctx, @Param String share) {
    return shares.get(RecipientTokenDecorator.recipient(ctx), share);
  }

  /** {@code GET /shares/{share}/schemas}: lists one page of the schemas in a granted share. */
  @Get("/shares/{share}/schemas")
  public ListResponse<SchemaResponse> listSchemas(
      ServiceRequestContext ctx,
      @Param String share,
      @Param @Nullable Integer maxResults,
      @Param @Nullable String pageToken) {
    return schemas.list(RecipientTokenDecorator.recipient(ctx), share, maxResults, pageToken);
  }
}
