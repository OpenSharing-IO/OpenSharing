package io.opensharing.protocol;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.ProducesJson;
import io.opensharing.ObjectNames;
import io.opensharing.asset.table.ProtocolTables;

/**
 * The Delta Sharing protocol endpoints for one shared table, which recipients call with their
 * bearer token, served behind {@link RecipientTokenDecorator}.
 */
@Blocking
@ProducesJson
public final class DeltaSharingProtocolService {

  private static final String DELTA_TABLE_VERSION = "Delta-Table-Version";

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
}
