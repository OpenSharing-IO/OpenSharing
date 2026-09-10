package io.opensharing.serving;

import io.opensharing.asset.CredentialVendingService;
import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.asset.SharedTableService;
import io.opensharing.asset.TableMapper;
import io.opensharing.http.ListResponse;
import io.opensharing.http.Listings;
import io.opensharing.http.ProtocolMediaType;
import io.opensharing.protocol.Table;
import io.opensharing.protocol.TemporaryCredentials;
import io.opensharing.protocol.TemporaryCredentialsRequest;
import io.opensharing.recipient.RecipientPrincipal;
import io.opensharing.share.ShareAccessService;
import io.opensharing.share.ShareEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Table discovery and directory-scoped credential vending, for tables of any format.
 *
 * <p>A table shared in its own right and one that a shared schema contains are both reachable here,
 * and a recipient cannot tell which it has.
 */
@RestController
@RequestMapping(value = RecipientApi.SHARE, produces = ProtocolMediaType.JSON_UTF8)
public class TablesController {

  private final SharedTableService tables;
  private final ShareAccessService access;
  private final TableMapper mapper;
  private final Listings listings;
  private final CredentialVendingService credentials;

  public TablesController(
      SharedTableService tables,
      ShareAccessService access,
      TableMapper mapper,
      Listings listings,
      CredentialVendingService credentials) {
    this.tables = tables;
    this.access = access;
    this.mapper = mapper;
    this.listings = listings;
    this.credentials = credentials;
  }

  @GetMapping("/all-tables")
  public ListResponse<Table> listAllTables(
      RecipientPrincipal principal,
      @PathVariable String share,
      @RequestParam(required = false) Integer maxResults,
      @RequestParam(required = false) String pageToken) {
    ShareEntity shareEntity = access.requireShare(principal, share);
    return listings.page(
        maxResults, pageToken, pageable -> tables.listAll(shareEntity, pageable), mapper::listing);
  }

  @GetMapping("/schemas/{schema}/tables")
  public ListResponse<Table> listTables(
      RecipientPrincipal principal,
      @PathVariable String share,
      @PathVariable String schema,
      @RequestParam(required = false) Integer maxResults,
      @RequestParam(required = false) String pageToken) {
    ShareEntity shareEntity = access.requireShare(principal, share);
    return listings.page(
        maxResults,
        pageToken,
        pageable -> tables.listInSchema(shareEntity, schema, pageable),
        mapper::listing);
  }

  @PostMapping("/schemas/{schema}/tables/{table}/temporary-table-credentials")
  public TemporaryCredentials tableCredentials(
      RecipientPrincipal principal,
      @PathVariable String share,
      @PathVariable String schema,
      @PathVariable String table,
      @RequestBody(required = false) TemporaryCredentialsRequest request) {
    SharedDataObjectEntity sharedTable =
        tables.require(access.requireShare(principal, share), schema, table);
    return credentials.vend(sharedTable, request == null ? null : request.location());
  }
}
