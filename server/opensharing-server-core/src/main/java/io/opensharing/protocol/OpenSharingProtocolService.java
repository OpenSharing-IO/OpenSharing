package io.opensharing.protocol;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.ProducesJson;
import io.opensharing.ObjectNames;
import io.opensharing.asset.schema.ProtocolSchemas;
import io.opensharing.asset.schema.SchemaResponse;
import io.opensharing.asset.share.GetShareResponse;
import io.opensharing.asset.share.ProtocolShares;
import io.opensharing.asset.table.GetTableResponse;
import io.opensharing.asset.table.ProtocolTables;
import io.opensharing.asset.table.TableResponse;
import io.opensharing.http.ListResponse;
import io.opensharing.share.ShareResponse;

/**
 * The OpenSharing protocol endpoints that list and get shares, schemas and tables, which recipients
 * call with their bearer token, served behind {@link RecipientTokenDecorator}.
 */
@Blocking
@ProducesJson
public final class OpenSharingProtocolService {

  private final ProtocolShares shares;
  private final ProtocolSchemas schemas;
  private final ProtocolTables tables;

  public OpenSharingProtocolService(
      ProtocolShares shares, ProtocolSchemas schemas, ProtocolTables tables) {
    this.shares = shares;
    this.schemas = schemas;
    this.tables = tables;
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

  /** {@code GET /shares/{share}/schemas/{schema}/tables}: lists one page of a schema's tables. */
  @Get("/shares/{share}/schemas/{schema}/tables")
  public ListResponse<TableResponse> listTables(
      ServiceRequestContext ctx,
      @Param String share,
      @Param String schema,
      @Param @Nullable Integer maxResults,
      @Param @Nullable String pageToken) {
    return tables.list(
        RecipientTokenDecorator.recipient(ctx),
        ObjectNames.normalize(share),
        ObjectNames.normalize(schema),
        maxResults,
        pageToken);
  }

  /** {@code GET /shares/{share}/all-tables}: lists one page of all the tables in a share. */
  @Get("/shares/{share}/all-tables")
  public ListResponse<TableResponse> listAllTables(
      ServiceRequestContext ctx,
      @Param String share,
      @Param @Nullable Integer maxResults,
      @Param @Nullable String pageToken) {
    return tables.listAll(
        RecipientTokenDecorator.recipient(ctx),
        ObjectNames.normalize(share),
        maxResults,
        pageToken);
  }

  /** {@code GET /shares/{share}/schemas/{schema}/tables/{table}}: gets a table in a schema. */
  @Get("/shares/{share}/schemas/{schema}/tables/{table}")
  public GetTableResponse getTable(
      ServiceRequestContext ctx, @Param String share, @Param String schema, @Param String table) {
    return tables.get(
        RecipientTokenDecorator.recipient(ctx),
        ObjectNames.normalize(share),
        ObjectNames.normalize(schema),
        ObjectNames.normalize(table));
  }
}
