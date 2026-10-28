package io.opensharing.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.Produces;
import com.linecorp.armeria.server.annotation.ProducesJson;
import io.opensharing.ObjectNames;
import io.opensharing.asset.table.DeltaSharingCapabilities;
import io.opensharing.asset.table.FileIdHash;
import io.opensharing.asset.table.ProtocolTables;
import io.opensharing.asset.table.QueryTableRequest;
import io.opensharing.asset.table.TemporaryTableCredentialsRequest;
import io.opensharing.asset.table.TemporaryTableCredentialsResponse;
import io.opensharing.asset.table.delta.DeltaTableMetadataReader;
import io.opensharing.asset.table.delta.DeltaTableQueryReader;
import io.opensharing.http.ApiJson;

/**
 * The Delta Sharing protocol endpoints for one shared table, which recipients call with their
 * bearer token, served behind {@link RecipientTokenDecorator}.
 */
@Blocking
@ProducesJson
public final class DeltaSharingProtocolService {

  static final String DELTA_TABLE_VERSION = "Delta-Table-Version";
  static final MediaType NDJSON = MediaType.parse("application/x-ndjson; charset=utf-8");

  private final ProtocolTables tables;

  public DeltaSharingProtocolService(ProtocolTables tables) {
    this.tables = tables;
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
            RecipientTokenDecorator.recipient(ctx),
            ObjectNames.normalize(share),
            ObjectNames.normalize(schema),
            ObjectNames.normalize(table),
            startingTimestamp);
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
            ObjectNames.normalize(share),
            ObjectNames.normalize(schema),
            ObjectNames.normalize(table),
            version,
            timestamp,
            capabilities);
    ResponseHeaders headers =
        ResponseHeaders.builder(HttpStatus.OK)
            .contentType(NDJSON)
            .add(DELTA_TABLE_VERSION, Long.toString(result.version()))
            .add(
                DeltaSharingCapabilities.HEADER,
                DeltaSharingCapabilities.responded(result.format()))
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
        RecipientTokenDecorator.recipient(ctx),
        ObjectNames.normalize(share),
        ObjectNames.normalize(schema),
        ObjectNames.normalize(table),
        options);
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
        new DeltaTableQueryReader.ResponseOptions(capabilities, FileIdHash.parse(fileIdHash));
    return NdjsonResponse.stream(
        ctx,
        options,
        sink ->
            tables.query(
                RecipientTokenDecorator.recipient(ctx),
                ObjectNames.normalize(share),
                ObjectNames.normalize(schema),
                ObjectNames.normalize(table),
                query,
                options,
                sink));
  }
}
