package io.opensharing.asset.table;

import io.opensharing.http.ApiException;
import io.opensharing.http.ListResponse;
import io.opensharing.http.Listings;
import io.opensharing.recipient.RecipientPrincipal;
import io.opensharing.recipient.RecipientStore;
import io.opensharing.share.ShareEntity;
import io.opensharing.share.SharePermissionStore;
import io.opensharing.share.ShareStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Recipient protocol API for listing tables in a SELECT-granted share. */
@RestController
@RequestMapping(
    value = "${opensharing.protocol-prefix}/shares/{share}",
    produces = "application/json;charset=UTF-8")
public class ProtocolTableController {

  private final RecipientStore recipients;
  private final ShareStore shares;
  private final SharePermissionStore permissions;
  private final SharedTableService tables;
  private final Listings listings;

  public ProtocolTableController(
      RecipientStore recipients,
      ShareStore shares,
      SharePermissionStore permissions,
      SharedTableService tables,
      Listings listings) {
    this.recipients = recipients;
    this.shares = shares;
    this.permissions = permissions;
    this.tables = tables;
    this.listings = listings;
  }

  @GetMapping("/all-tables")
  public ListResponse<TableResponse> listAll(
      RecipientPrincipal principal,
      @PathVariable String share,
      @RequestParam(required = false) Integer maxResults,
      @RequestParam(required = false) String pageToken) {
    ShareEntity entity = requireGrantedShare(principal, share);
    return listings.page(
        maxResults, pageToken, pageable -> tables.listAll(entity, pageable), listed -> listed);
  }

  @GetMapping("/schemas/{schema}/tables")
  public ListResponse<TableResponse> list(
      RecipientPrincipal principal,
      @PathVariable String share,
      @PathVariable String schema,
      @RequestParam(required = false) Integer maxResults,
      @RequestParam(required = false) String pageToken) {
    ShareEntity entity = requireGrantedShare(principal, share);
    return listings.page(
        maxResults,
        pageToken,
        pageable -> tables.listInSchema(entity, schema, pageable),
        listed -> listed);
  }

  private ShareEntity requireGrantedShare(RecipientPrincipal principal, String share) {
    var recipient = recipients.requireById(principal.recipientId());
    return shares
        .find(share)
        .filter(candidate -> permissions.hasSelect(candidate, recipient))
        .orElseThrow(() -> ApiException.notFound("share '" + share + "' does not exist"));
  }
}
