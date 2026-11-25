package io.opensharing.protocol.iceberg;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import io.opensharing.ObjectNames;
import io.opensharing.asset.schema.ProtocolSchemas;
import io.opensharing.asset.schema.SchemaResponse;
import io.opensharing.asset.share.ProtocolShares;
import io.opensharing.asset.table.StorageLocations;
import io.opensharing.asset.table.iceberg.IcebergStorageCredential;
import io.opensharing.asset.table.iceberg.IcebergTable;
import io.opensharing.asset.table.iceberg.ProtocolIcebergTables;
import io.opensharing.http.ApiException;
import io.opensharing.http.ListResponse;
import io.opensharing.protocol.RecipientTokenDecorator;
import io.opensharing.recipient.RecipientPrincipal;
import io.opensharing.share.ShareEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The read-only Iceberg REST catalog of {@code spec/protocols/TABLES.md}, where a profile file's
 * {@code icebergEndpoint} points, which recipients call with their bearer token. A share is a
 * warehouse, addressed under the {@code shares/{share}} prefix, and its schemas are namespaces.
 */
@Blocking
@ProducesJson
public final class IcebergRestCatalogService {

  private static final Logger log = LoggerFactory.getLogger(IcebergRestCatalogService.class);

  /** The operations this catalog serves, which {@code getConfig} lists for clients. */
  static final List<String> ENDPOINTS =
      List.of(
          "GET /v1/{prefix}/namespaces",
          "GET /v1/{prefix}/namespaces/{namespace}",
          "GET /v1/{prefix}/namespaces/{namespace}/tables",
          "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}",
          "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}/credentials",
          "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/metrics");

  /** The header an Iceberg client names the ways it accepts access to a table's files in. */
  private static final String ACCESS_DELEGATION = "X-Iceberg-Access-Delegation";

  private static final String VENDED_CREDENTIALS = "vended-credentials";

  private final ProtocolShares shares;
  private final ProtocolSchemas schemas;
  private final ProtocolIcebergTables catalog;

  public IcebergRestCatalogService(
      ProtocolShares shares, ProtocolSchemas schemas, ProtocolIcebergTables catalog) {
    this.shares = shares;
    this.schemas = schemas;
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

  /**
   * {@code GET /v1/shares/{share}/namespaces}: the share's schemas, all of them without a pageToken
   * and else one page. A share's namespaces are one level deep, so listing under a {@code parent}
   * is rejected.
   */
  @Get("/v1/shares/{share}/namespaces")
  public NamespacesResponse listNamespaces(
      ServiceRequestContext ctx,
      @Param String share,
      @Param @Nullable String parent,
      @Param @Nullable Integer pageSize,
      @Param @Nullable String pageToken) {
    requirePageSize(pageSize);
    if (parent != null && !parent.isEmpty()) {
      throw IcebergException.badRequest(
          "a share's namespaces are its schemas, which are one level deep, so none has a parent");
    }
    ListResponse<SchemaResponse> page =
        listPage(
            pageSize,
            pageToken,
            (size, token) ->
                asNoSuchNamespace(
                    () ->
                        schemas.list(
                            RecipientTokenDecorator.recipient(ctx),
                            ObjectNames.normalize(share),
                            size,
                            token)));
    return new NamespacesResponse(
        page.items().stream().map(schema -> List.of(schema.name())).toList(),
        page.nextPageToken());
  }

  /** {@code GET /v1/shares/{share}/namespaces/{namespace}}: a schema of the share. */
  @Get("/v1/shares/{share}/namespaces/{namespace}")
  public NamespaceResponse loadNamespaceMetadata(
      ServiceRequestContext ctx, @Param String share, @Param String namespace) {
    SchemaResponse schema =
        asNoSuchNamespace(
            () ->
                schemas.get(
                    RecipientTokenDecorator.recipient(ctx),
                    ObjectNames.normalize(share),
                    ObjectNames.normalize(namespace)));
    return new NamespaceResponse(List.of(schema.name()), Map.of());
  }

  /**
   * {@code GET /v1/shares/{share}/namespaces/{namespace}/tables}: its Iceberg tables, all of them
   * without a pageToken and else one page.
   */
  @Get("/v1/shares/{share}/namespaces/{namespace}/tables")
  public TablesResponse listTables(
      ServiceRequestContext ctx,
      @Param String share,
      @Param String namespace,
      @Param @Nullable Integer pageSize,
      @Param @Nullable String pageToken) {
    requirePageSize(pageSize);
    String schemaAlias = ObjectNames.normalize(namespace);
    ListResponse<String> page =
        asNoSuchNamespace(
            () ->
                pageToken == null
                    ? new ListResponse<>(
                        catalog.listAllTables(
                            RecipientTokenDecorator.recipient(ctx),
                            ObjectNames.normalize(share),
                            schemaAlias),
                        null)
                    : catalog.listTables(
                        RecipientTokenDecorator.recipient(ctx),
                        ObjectNames.normalize(share),
                        schemaAlias,
                        pageSize,
                        pageToken));
    return new TablesResponse(
        page.items().stream()
            .map(table -> new TablesResponse.Identifier(List.of(schemaAlias), table))
            .toList(),
        page.nextPageToken());
  }

  /**
   * {@code GET /v1/shares/{share}/namespaces/{namespace}/tables/{table}}: an Iceberg table's
   * current metadata, and read credentials for its location when the client asks for them with
   * {@code X-Iceberg-Access-Delegation: vended-credentials}, as the Iceberg REST spec defines.
   */
  @Get("/v1/shares/{share}/namespaces/{namespace}/tables/{table}")
  public LoadTableResponse loadTable(
      ServiceRequestContext ctx,
      @Param String share,
      @Param String namespace,
      @Param String table,
      @Header(ACCESS_DELEGATION) @Nullable String accessDelegation) {
    boolean withCredentials = requestsVendedCredentials(accessDelegation);
    IcebergTable loaded =
        asNoSuchTable(
            () ->
                catalog.loadTable(
                    RecipientTokenDecorator.recipient(ctx),
                    ObjectNames.normalize(share),
                    ObjectNames.normalize(namespace),
                    ObjectNames.normalize(table),
                    withCredentials));
    if (!withCredentials) {
      return new LoadTableResponse(loaded.metadataLocation(), loaded.metadata(), null, null);
    }
    return new LoadTableResponse(
        loaded.metadataLocation(),
        loaded.metadata(),
        IcebergStorageCredential.legacyConfig(
            loaded.credentials(),
            "v1/shares"
                + StorageLocations.encodePath(ObjectNames.normalize(share))
                + "/namespaces"
                + StorageLocations.encodePath(ObjectNames.normalize(namespace))
                + "/tables"
                + StorageLocations.encodePath(ObjectNames.normalize(table))
                + "/credentials"),
        loaded.credentials());
  }

  /** Whether the client's comma-separated access delegation mechanisms include vended ones. */
  private static boolean requestsVendedCredentials(String accessDelegation) {
    if (accessDelegation == null) {
      return false;
    }
    for (String mechanism : accessDelegation.split(",")) {
      if (mechanism.trim().equalsIgnoreCase(VENDED_CREDENTIALS)) {
        return true;
      }
    }
    return false;
  }

  /**
   * {@code GET /v1/shares/{share}/namespaces/{namespace}/tables/{table}/credentials}: read
   * credentials for an Iceberg table's location, without its metadata.
   */
  @Get("/v1/shares/{share}/namespaces/{namespace}/tables/{table}/credentials")
  public LoadCredentialsResponse loadCredentials(
      ServiceRequestContext ctx,
      @Param String share,
      @Param String namespace,
      @Param String table) {
    return new LoadCredentialsResponse(
        asNoSuchTable(
            () ->
                catalog.loadCredentials(
                    RecipientTokenDecorator.recipient(ctx),
                    ObjectNames.normalize(share),
                    ObjectNames.normalize(namespace),
                    ObjectNames.normalize(table))));
  }

  /**
   * {@code POST /v1/shares/{share}/namespaces/{namespace}/tables/{table}/metrics}: logs the report
   * an Iceberg client sends after reading a table, as received, and does nothing else with it. The
   * scan ran on the recipient's engine, so there is nothing for the provider to act on.
   */
  @Post("/v1/shares/{share}/namespaces/{namespace}/tables/{table}/metrics")
  public HttpResponse reportMetrics(
      ServiceRequestContext ctx,
      @Param String share,
      @Param String namespace,
      @Param String table,
      String report) {
    RecipientPrincipal recipient = RecipientTokenDecorator.recipient(ctx);
    String shareName = ObjectNames.normalize(share);
    String schemaAlias = ObjectNames.normalize(namespace);
    String tableAlias = ObjectNames.normalize(table);
    asNoSuchTable(() -> catalog.requireTable(recipient, shareName, schemaAlias, tableAlias));
    log.info(
        "Recipient {} reported metrics for '{}.{}' in share '{}': {}",
        recipient.recipientId(),
        schemaAlias,
        tableAlias,
        shareName,
        report);
    return HttpResponse.of(HttpStatus.NO_CONTENT);
  }

  private static void asNoSuchTable(Runnable lookup) {
    asNoSuchTable(
        () -> {
          lookup.run();
          return null;
        });
  }

  /** Runs {@code lookup}, reporting what it can't find as a missing table. */
  private static <T> T asNoSuchTable(Supplier<T> lookup) {
    try {
      return lookup.get();
    } catch (ApiException missing) {
      if (missing.getStatus() == 404) {
        throw IcebergException.noSuchTable(missing.getMessage());
      }
      throw missing;
    }
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
