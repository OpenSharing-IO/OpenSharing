package io.opensharing.protocol.iceberg;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.ProducesJson;
import io.opensharing.ObjectNames;
import io.opensharing.asset.share.ProtocolShares;
import io.opensharing.http.ApiException;
import io.opensharing.protocol.RecipientTokenDecorator;
import io.opensharing.share.ShareEntity;
import java.util.List;
import java.util.Map;

/**
 * The read-only Iceberg REST catalog of {@code spec/protocols/TABLES.md}, where a profile file's
 * {@code icebergEndpoint} points, which recipients call with their bearer token. A share is a
 * warehouse, addressed under the {@code shares/{share}} prefix, and its schemas are namespaces.
 */
@Blocking
@ProducesJson
public final class IcebergRestCatalogService {

  /** The operations this catalog serves, which {@code getConfig} lists for clients. */
  static final List<String> ENDPOINTS = List.of();

  private final ProtocolShares shares;

  public IcebergRestCatalogService(ProtocolShares shares) {
    this.shares = shares;
  }

  /**
   * {@code GET /v1/config?warehouse={share}}: the prefix a client addresses a share it holds
   * SELECT on under, and the endpoints it may call there.
   *
   * <p>The spec has no 404 for getConfig, so a share that doesn't exist and one the recipient isn't
   * granted are both 403, with one message, which doesn't reveal which shares exist.
   */
  @Get("/v1/config")
  public ConfigResponse getConfig(ServiceRequestContext ctx, @Param @Nullable String warehouse) {
    if (warehouse == null || warehouse.isBlank()) {
      throw IcebergException.badRequest("the warehouse parameter must name the share to read");
    }
    ShareEntity share;
    try {
      share =
          shares.requireGranted(
              RecipientTokenDecorator.recipient(ctx), ObjectNames.normalize(warehouse));
    } catch (ApiException missing) {
      if (missing.getStatus() != 404) {
        throw missing;
      }
      throw IcebergException.forbidden(
          "the warehouse is not a share the recipient holds SELECT on");
    }
    return new ConfigResponse(Map.of(), Map.of("prefix", "shares/" + share.getName()), ENDPOINTS);
  }
}
