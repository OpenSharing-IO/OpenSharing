package io.opensharing.serving;

import com.fasterxml.jackson.databind.JsonNode;
import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.asset.SharedDataObjectStore;
import io.opensharing.asset.SharedTableService;
import io.opensharing.asset.iceberg.IcebergTableLoader;
import io.opensharing.catalog.TableFormat;
import io.opensharing.http.ApiException;
import io.opensharing.http.ListResponse;
import io.opensharing.http.Listings;
import io.opensharing.http.ProtocolMediaType;
import io.opensharing.protocol.IcebergConfig;
import io.opensharing.protocol.IcebergLoadTable;
import io.opensharing.protocol.IcebergNamespaceMetadata;
import io.opensharing.protocol.IcebergNamespaces;
import io.opensharing.protocol.IcebergTables;
import io.opensharing.recipient.RecipientPrincipal;
import io.opensharing.share.ShareAccessService;
import io.opensharing.share.ShareEntity;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Iceberg REST catalog surface of {@code spec/protocols/TABLES.md}, mounted where the profile
 * file's {@code icebergEndpoint} points.
 *
 * <p>A share is a warehouse and its schemas are namespaces, so what an Iceberg client browses is the
 * same thing the protocol's own share, schema and table endpoints show — read through the interface
 * an Iceberg engine already has a client for. Only the share's Iceberg tables are listed, since a
 * table of another format is not one this catalog could load.
 *
 * <p>This is a read-only catalog: there is no create, drop, rename or commit, because a recipient
 * cannot change a provider's tables. That is stated in the {@code /v1/config} handshake, whose
 * {@code endpoints} tell a client exactly which operations exist here.
 */
@RestController
@RequestMapping(value = RecipientApi.ICEBERG, produces = ProtocolMediaType.JSON_UTF8)
public class IcebergCatalogController {

  private static final Logger log = LoggerFactory.getLogger(IcebergCatalogController.class);

  private static final List<String> ENDPOINTS =
      List.of(
          "GET /v1/{prefix}/namespaces",
          "GET /v1/{prefix}/namespaces/{namespace}",
          "GET /v1/{prefix}/namespaces/{namespace}/tables",
          "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}",
          "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/metrics");

  /** How the Iceberg spec joins the levels of a namespace inside a single path segment. */
  private static final char NAMESPACE_SEPARATOR = '\u001f';

  private final ShareAccessService access;
  private final SharedDataObjectStore objects;
  private final SharedTableService tables;
  private final IcebergTableLoader loader;
  private final Listings listings;

  public IcebergCatalogController(
      ShareAccessService access,
      SharedDataObjectStore objects,
      SharedTableService tables,
      IcebergTableLoader loader,
      Listings listings) {
    this.access = access;
    this.objects = objects;
    this.tables = tables;
    this.loader = loader;
    this.listings = listings;
  }

  /**
   * @param warehouse the share to read, echoed back as the {@code shares/{share}} path prefix under
   *     which the client addresses every later call
   */
  @GetMapping("/config")
  public IcebergConfig config(RecipientPrincipal principal, @RequestParam String warehouse) {
    ShareEntity share = access.requireShare(principal, warehouse);
    return new IcebergConfig(Map.of(), Map.of("prefix", "shares/" + share.getName()), ENDPOINTS);
  }

  /**
   * @param parent answered empty when given: a share's namespaces are its schemas, and a schema
   *     holds tables rather than further namespaces
   */
  @GetMapping("/shares/{share}/namespaces")
  public IcebergNamespaces listNamespaces(
      RecipientPrincipal principal,
      @PathVariable String share,
      @RequestParam(required = false) String parent,
      @RequestParam(required = false) Integer pageSize,
      @RequestParam(required = false) String pageToken) {
    ShareEntity shareEntity = access.requireShare(principal, share);
    if (parent != null && !parent.isBlank()) {
      return new IcebergNamespaces(List.of(), null);
    }
    ListResponse<List<String>> page =
        listings.page(
            pageSize,
            pageToken,
            pageable -> objects.listSchemaNames(shareEntity, pageable),
            List::of);
    return new IcebergNamespaces(page.items(), page.nextPageToken());
  }

  @GetMapping("/shares/{share}/namespaces/{namespace}")
  public IcebergNamespaceMetadata loadNamespaceMetadata(
      RecipientPrincipal principal, @PathVariable String share, @PathVariable String namespace) {
    ShareEntity shareEntity = access.requireShare(principal, share);
    String schema = schemaOf(namespace);
    objects.requireSchema(shareEntity, schema);
    return new IcebergNamespaceMetadata(List.of(schema), Map.of());
  }

  /**
   * The Iceberg tables of one namespace. A page is filtered rather than filled, so it can come back
   * shorter than asked for while a page token is still offered; a client following the tokens still
   * sees every Iceberg table in the namespace exactly once, which is what the contract promises.
   */
  @GetMapping("/shares/{share}/namespaces/{namespace}/tables")
  public IcebergTables listTables(
      RecipientPrincipal principal,
      @PathVariable String share,
      @PathVariable String namespace,
      @RequestParam(required = false) Integer pageSize,
      @RequestParam(required = false) String pageToken) {
    ShareEntity shareEntity = access.requireShare(principal, share);
    String schema = schemaOf(namespace);
    ListResponse<SharedDataObjectEntity> page =
        listings.page(
            pageSize,
            pageToken,
            pageable -> tables.listInSchema(shareEntity, schema, pageable),
            Function.identity());
    List<IcebergTables.Identifier> identifiers =
        page.items().stream()
            .filter(table -> table.getSourceFormat() == TableFormat.ICEBERG)
            .map(table -> new IcebergTables.Identifier(List.of(schema), table.getSharedAsName()))
            .toList();
    return new IcebergTables(identifiers, page.nextPageToken());
  }

  @GetMapping("/shares/{share}/namespaces/{namespace}/tables/{table}")
  public IcebergLoadTable loadTable(
      RecipientPrincipal principal,
      @PathVariable String share,
      @PathVariable String namespace,
      @PathVariable String table) {
    return loader.load(requireTable(principal, share, namespace, table));
  }

  /**
   * Scan reports are accepted and dropped. A client sends them unasked after a query, so refusing
   * them would make an ordinary read look like a failure; and the numbers are none of this server's
   * business, since the scan happened on the recipient's own engine.
   */
  @PostMapping("/shares/{share}/namespaces/{namespace}/tables/{table}/metrics")
  public ResponseEntity<Void> reportMetrics(
      RecipientPrincipal principal,
      @PathVariable String share,
      @PathVariable String namespace,
      @PathVariable String table,
      @RequestBody(required = false) JsonNode report) {
    requireTable(principal, share, namespace, table);
    log.debug("Dropping an Iceberg scan report for {}.{}.{}", share, namespace, table);
    return ResponseEntity.noContent().build();
  }

  private SharedDataObjectEntity requireTable(
      RecipientPrincipal principal, String share, String namespace, String table) {
    return tables.require(access.requireShare(principal, share), schemaOf(namespace), table);
  }

  /**
   * The one level of a namespace this catalog has. Iceberg joins a multi-level namespace into one
   * path segment, so a deeper one arrives here as a name with separators in it, and is simply not a
   * namespace this share holds.
   */
  private static String schemaOf(String namespace) {
    if (namespace.indexOf(NAMESPACE_SEPARATOR) < 0) {
      return namespace;
    }
    throw ApiException.notFound(
        "namespace '"
            + namespace.replace(NAMESPACE_SEPARATOR, '.')
            + "' does not exist: a share's namespaces are its schemas, which are one level deep");
  }
}
