package io.opensharing.protocol.iceberg;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.ProducesJson;
import io.opensharing.ObjectNames;
import io.opensharing.asset.table.ProtocolIcebergCatalog;
import io.opensharing.http.ApiException;
import io.opensharing.http.ListResponse;
import io.opensharing.protocol.RecipientTokenDecorator;
import io.opensharing.share.ShareEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * The read-only Iceberg REST catalog of {@code spec/protocols/TABLES.md}, where a profile file's
 * {@code icebergEndpoint} points, which recipients call with their bearer token. A share is a
 * warehouse, addressed under the {@code shares/{share}} prefix, and its schemas are namespaces.
 */
@Blocking
@ProducesJson
public final class IcebergRestCatalogService {

  /** The operations this catalog serves, which {@code getConfig} lists for clients. */
  static final List<String> ENDPOINTS =
      List.of("GET /v1/{prefix}/namespaces");

  /** How Iceberg joins the levels of a namespace in one path segment. */
  private static final char NAMESPACE_SEPARATOR = '\u001f';

  private final ProtocolIcebergCatalog catalog;

  public IcebergRestCatalogService(ProtocolIcebergCatalog catalog) {
    this.catalog = catalog;
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
          catalog.getShare(
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

  /**
   * {@code GET /v1/shares/{share}/namespaces}: one page of the share's schemas. A schema holds no
   * namespaces, so listing under a {@code parent} schema returns none.
   */
  @Get("/v1/shares/{share}/namespaces")
  public NamespacesResponse listNamespaces(
      ServiceRequestContext ctx,
      @Param String share,
      @Param @Nullable String parent,
      @Param @Nullable Integer pageSize,
      @Param @Nullable String pageToken) {
    requirePageSize(pageSize);
    ShareEntity granted = share(ctx, share);
    if (parent != null && !parent.isEmpty()) {
      namespace(granted, parent);
      return new NamespacesResponse(List.of(), null);
    }
    ListResponse<String> page =
        listPage(
            pageSize, pageToken, (size, token) -> catalog.listNamespaces(granted, size, token));
    return new NamespacesResponse(
        page.items().stream().map(List::of).toList(), page.nextPageToken());
  }

  /** The share in the path prefix. A share the recipient can't read has no namespaces. */
  private ShareEntity share(ServiceRequestContext ctx, String share) {
    return asNoSuchNamespace(
        () ->
            catalog.getShare(
                RecipientTokenDecorator.recipient(ctx), ObjectNames.normalize(share)));
  }

  /**
   * The schema alias {@code namespace} names in {@code share}. A share's schemas are one level
   * deep, so a namespace of more levels is never one of them.
   */
  private String namespace(ShareEntity share, String namespace) {
    if (namespace.indexOf(NAMESPACE_SEPARATOR) >= 0) {
      throw IcebergException.noSuchNamespace(
          "namespace '"
              + namespace.replace(NAMESPACE_SEPARATOR, '.')
              + "' does not exist in share '"
              + share.getName()
              + "'");
    }
    return asNoSuchNamespace(
        () -> catalog.getNamespace(share, ObjectNames.normalize(namespace)));
  }

  /**
   * The page a client asked for. A request without a pageToken gets every result and no
   * next-page-token whatever its pageSize, as the Iceberg REST spec requires of a server that
   * supports pagination; an empty pageToken is how a client starts paging.
   */
  private static <T> ListResponse<T> listPage(
      Integer pageSize, String pageToken, BiFunction<Integer, String, ListResponse<T>> list) {
    if (pageToken != null) {
      return list.apply(pageSize, pageToken);
    }
    List<T> items = new ArrayList<>();
    String next = "";
    do {
      ListResponse<T> page = list.apply(null, next);
      items.addAll(page.items());
      next = page.nextPageToken();
    } while (next != null);
    return new ListResponse<>(items, null);
  }

  private static void requirePageSize(Integer pageSize) {
    if (pageSize != null && pageSize < 1) {
      throw IcebergException.badRequest("pageSize must be at least 1");
    }
  }

  /** Runs {@code lookup}, reporting what it can't find as a missing namespace. */
  private static <T> T asNoSuchNamespace(Supplier<T> lookup) {
    try {
      return lookup.get();
    } catch (ApiException missing) {
      if (missing.getStatus() == 404) {
        throw IcebergException.noSuchNamespace(missing.getMessage());
      }
      throw missing;
    }
  }
}
