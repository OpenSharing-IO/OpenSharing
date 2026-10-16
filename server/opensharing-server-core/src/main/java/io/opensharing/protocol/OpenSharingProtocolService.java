package io.opensharing.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.Produces;
import com.linecorp.armeria.server.annotation.ProducesJson;
import io.opensharing.asset.schema.ProtocolSchemas;
import io.opensharing.asset.schema.SchemaResponse;
import io.opensharing.asset.share.GetShareResponse;
import io.opensharing.asset.share.ProtocolShares;
import io.opensharing.asset.table.DeltaSharingCapabilities;
import io.opensharing.asset.table.FileIdHash;
import io.opensharing.asset.table.ProtocolTables;
import io.opensharing.asset.table.QueryTableRequest;
import io.opensharing.asset.table.TableResponse;
import io.opensharing.asset.table.TemporaryTableCredentialsRequest;
import io.opensharing.asset.table.TemporaryTableCredentialsResponse;
import io.opensharing.asset.table.delta.DeltaTableMetadataReader;
import io.opensharing.asset.table.delta.DeltaTableQueryReader;
import io.opensharing.http.ApiJson;
import io.opensharing.http.ListResponse;
import io.opensharing.share.ShareResponse;

/**
 * The Delta Sharing protocol API: every endpoint recipients call with their bearer token, served
 * behind {@link RecipientTokenDecorator}.
 */
@Blocking
@ProducesJson
public final class OpenSharingProtocolService {

  private static final String DELTA_TABLE_VERSION = "Delta-Table-Version";
  private static final MediaType NDJSON = MediaType.parse("application/x-ndjson; charset=utf-8");

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
        RecipientTokenDecorator.recipient(ctx), share, schema, maxResults, pageToken);
  }

  /** {@code GET /shares/{share}/all-tables}: lists one page of all the tables in a share. */
  @Get("/shares/{share}/all-tables")
  public ListResponse<TableResponse> listAllTables(
      ServiceRequestContext ctx,
      @Param String share,
      @Param @Nullable Integer maxResults,
      @Param @Nullable String pageToken) {
    return tables.listAll(RecipientTokenDecorator.recipient(ctx), share, maxResults, pageToken);
  }

  /**
   * {@code GET /shares/{share}/schemas/{schema}/tables/{table}/version}: the table's Delta version
   * in the {@code Delta-Table-Version} header, with no body.
   */
  @Get("/shares/{share}/schemas/{schema}/tables/{table}/version")
  public HttpResponse getTableVersion(
      ServiceRequestContext ctx,
      @Param String share,
      @Param String schema,
      @Param String table,
      @Param @Nullable String startingTimestamp) {
    long version =
        tables.version(
            RecipientTokenDecorator.recipient(ctx), share, schema, table, startingTimestamp);
    return HttpResponse.of(
        ResponseHeaders.of(HttpStatus.OK, DELTA_TABLE_VERSION, Long.toString(version)));
  }

  /**
   * {@code GET /shares/{share}/schemas/{schema}/tables/{table}/metadata}: the table's protocol and
   * metadata as NDJSON, with its version in the {@code Delta-Table-Version} header.
   */
  @Get("/shares/{share}/schemas/{schema}/tables/{table}/metadata")
  @Produces("application/x-ndjson")
  public HttpResponse getTableMetadata(
      ServiceRequestContext ctx,
      @Param String share,
      @Param String schema,
      @Param String table,
      @Param @Nullable Long version,
      @Param @Nullable String timestamp,
      @Header(DeltaSharingCapabilities.HEADER) @Nullable String capabilities) {
    DeltaTableMetadataReader.Result result =
        tables.metadata(
            RecipientTokenDecorator.recipient(ctx),
            share,
            schema,
            table,
            version,
            timestamp,
            capabilities);
    ResponseHeaders headers =
        ResponseHeaders.builder(HttpStatus.OK)
            .contentType(NDJSON)
            .add(DELTA_TABLE_VERSION, Long.toString(result.version()))
            .add(DeltaSharingCapabilities.HEADER, DeltaSharingCapabilities.responded(capabilities))
            .build();
    return HttpResponse.of(headers, HttpData.ofUtf8(result.ndjson()));
  }

  /**
   * {@code POST /shares/{share}/schemas/{schema}/tables/{table}/temporary-table-credentials}: read
   * credentials for a table that supports directory access. The request body is optional.
   */
  @Post("/shares/{share}/schemas/{schema}/tables/{table}/temporary-table-credentials")
  public TemporaryTableCredentialsResponse generateTemporaryTableCredentials(
      ServiceRequestContext ctx,
      @Param String share,
      @Param String schema,
      @Param String table,
      AggregatedHttpRequest request)
      throws JsonProcessingException {
    String body = request.contentUtf8();
    TemporaryTableCredentialsRequest options =
        body.isBlank()
            ? null
            : ApiJson.MAPPER.readValue(body, TemporaryTableCredentialsRequest.class);
    return tables.temporaryCredentials(
        RecipientTokenDecorator.recipient(ctx), share, schema, table, options);
  }

  /**
   * {@code POST /shares/{share}/schemas/{schema}/tables/{table}/query}: one snapshot of the table
   * as NDJSON with presigned file URLs, and its version in the {@code Delta-Table-Version} header.
   * The request body is optional.
   */
  @Post("/shares/{share}/schemas/{schema}/tables/{table}/query")
  @Produces("application/x-ndjson")
  public HttpResponse queryTable(
      ServiceRequestContext ctx,
      @Param String share,
      @Param String schema,
      @Param String table,
      @Header(DeltaSharingCapabilities.HEADER) @Nullable String capabilities,
      @Header(FileIdHash.HEADER) @Nullable String fileIdHash,
      AggregatedHttpRequest request)
      throws JsonProcessingException {
    String body = request.contentUtf8();
    QueryTableRequest query =
        body.isBlank() ? null : ApiJson.MAPPER.readValue(body, QueryTableRequest.class);
    DeltaTableQueryReader.ResponseOptions options =
        new DeltaTableQueryReader.ResponseOptions(
            capabilities,
            FileIdHash.parse(fileIdHash),
            DeltaSharingCapabilities.includeEndStreamAction(capabilities));
    DeltaTableQueryReader.Result result =
        tables.query(RecipientTokenDecorator.recipient(ctx), share, schema, table, query, options);
    ResponseHeadersBuilder headers =
        ResponseHeaders.builder(HttpStatus.OK)
            .contentType(NDJSON)
            .add(DELTA_TABLE_VERSION, Long.toString(result.version()))
            .add(
                DeltaSharingCapabilities.HEADER,
                DeltaSharingCapabilities.responded(capabilities, options.includeEndStreamAction()));
    if (options.fileIdHash() != null) {
      headers.add(FileIdHash.HEADER, options.fileIdHash());
    }
    return HttpResponse.of(headers.build(), HttpData.ofUtf8(result.ndjson()));
  }
}
