package io.opensharing.share;

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

/** Recipient protocol API for listing and getting shares granted with SELECT. */
@RestController
@RequestMapping(
    value = "${opensharing.protocol-prefix}/shares",
    produces = "application/json;charset=UTF-8")
public class ProtocolShareController {

  private final RecipientStore recipients;
  private final ShareStore shares;
  private final SharePermissionStore permissions;
  private final Listings listings;

  public ProtocolShareController(
      RecipientStore recipients,
      ShareStore shares,
      SharePermissionStore permissions,
      Listings listings) {
    this.recipients = recipients;
    this.shares = shares;
    this.permissions = permissions;
    this.listings = listings;
  }

  @GetMapping
  public ListResponse<ShareResponse> list(
      RecipientPrincipal principal,
      @RequestParam(required = false) Integer maxResults,
      @RequestParam(required = false) String pageToken) {
    var recipient = recipients.requireById(principal.recipientId());
    return listings.page(
        maxResults,
        pageToken,
        pageable -> permissions.listSharesFor(recipient, pageable),
        ShareResponse::from);
  }

  @GetMapping("/{share}")
  public GetShareResponse get(RecipientPrincipal principal, @PathVariable String share) {
    var recipient = recipients.requireById(principal.recipientId());
    ShareEntity entity =
        shares
            .find(share)
            .filter(candidate -> permissions.hasSelect(candidate, recipient))
            .orElseThrow(() -> ApiException.notFound("share '" + share + "' does not exist"));
    return new GetShareResponse(ShareResponse.from(entity));
  }
}
