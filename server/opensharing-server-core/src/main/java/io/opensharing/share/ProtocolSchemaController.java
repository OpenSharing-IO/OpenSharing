package io.opensharing.share;

import io.opensharing.asset.SharedDataObjectStore;
import io.opensharing.http.ApiException;
import io.opensharing.http.ListResponse;
import io.opensharing.http.Listings;
import io.opensharing.recipient.RecipientPrincipal;
import io.opensharing.recipient.RecipientStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Recipient protocol API for listing schemas in a SELECT-granted share. */
@RestController
@RequestMapping(
    value = "${opensharing.protocol-prefix}/shares/{share}/schemas",
    produces = "application/json;charset=UTF-8")
public class ProtocolSchemaController {

  private final RecipientStore recipients;
  private final ShareStore shares;
  private final SharePermissionStore permissions;
  private final SharedDataObjectStore objects;
  private final Listings listings;

  public ProtocolSchemaController(
      RecipientStore recipients,
      ShareStore shares,
      SharePermissionStore permissions,
      SharedDataObjectStore objects,
      Listings listings) {
    this.recipients = recipients;
    this.shares = shares;
    this.permissions = permissions;
    this.objects = objects;
    this.listings = listings;
  }

  @GetMapping
  public ListResponse<SchemaResponse> list(
      RecipientPrincipal principal,
      @PathVariable String share,
      @RequestParam(required = false) Integer maxResults,
      @RequestParam(required = false) String pageToken) {
    var recipient = recipients.requireById(principal.recipientId());
    ShareEntity entity =
        shares
            .find(share)
            .filter(candidate -> permissions.hasSelect(candidate, recipient))
            .orElseThrow(() -> ApiException.notFound("share '" + share + "' does not exist"));
    return listings.page(
        maxResults,
        pageToken,
        pageable -> objects.listSchemas(entity, pageable),
        name -> new SchemaResponse(name, entity.getName()));
  }
}
