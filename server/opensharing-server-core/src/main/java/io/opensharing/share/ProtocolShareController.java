package io.opensharing.share;

import io.opensharing.http.ListResponse;
import io.opensharing.http.Listings;
import io.opensharing.protocol.Share;
import io.opensharing.recipient.RecipientPrincipal;
import io.opensharing.recipient.RecipientStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Recipient protocol API for listing shares granted with SELECT. */
@RestController
@RequestMapping(
    value = "${opensharing.protocol-prefix}/shares",
    produces = "application/json;charset=UTF-8")
public class ProtocolShareController {

  private final RecipientStore recipients;
  private final SharePermissionStore permissions;
  private final Listings listings;
  private final ShareMapper mapper;

  public ProtocolShareController(
      RecipientStore recipients,
      SharePermissionStore permissions,
      Listings listings,
      ShareMapper mapper) {
    this.recipients = recipients;
    this.permissions = permissions;
    this.listings = listings;
    this.mapper = mapper;
  }

  @GetMapping
  public ListResponse<Share> list(
      RecipientPrincipal principal,
      @RequestParam(required = false) Integer maxResults,
      @RequestParam(required = false) String pageToken) {
    var recipient = recipients.requireById(principal.recipientId());
    return listings.page(
        maxResults,
        pageToken,
        pageable -> permissions.listSharesFor(recipient, pageable),
        mapper::toProtocol);
  }
}
