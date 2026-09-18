package io.opensharing.share;

import io.opensharing.ObjectNames;
import io.opensharing.auth.UserContext;
import io.opensharing.http.ListResponse;
import io.opensharing.recipient.RecipientStore;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Provider HTTP API for granting recipients access to a share. */
@RestController
@RequestMapping("${opensharing.provider.base-path}/shares/{share}/recipients")
public class ShareRecipientAdminController {

  private final ShareStore shares;
  private final RecipientStore recipients;
  private final ShareRecipientStore grants;

  public ShareRecipientAdminController(
      ShareStore shares, RecipientStore recipients, ShareRecipientStore grants) {
    this.shares = shares;
    this.recipients = recipients;
    this.grants = grants;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ShareRecipientResponse create(
      UserContext user,
      @PathVariable String share,
      @Valid @RequestBody GrantShareRecipientRequest request) {
    return ShareRecipientResponse.from(
        grants.add(
            shares.requireOwned(share, user),
            recipients.require(ObjectNames.validateRecipientName(request.name()))));
  }

  @GetMapping
  public ListResponse<ShareRecipientResponse> list(UserContext user, @PathVariable String share) {
    return ListResponse.of(
        grants.list(shares.require(share)).stream().map(ShareRecipientResponse::from).toList());
  }

  @DeleteMapping("/{recipient}")
  public ResponseEntity<Void> delete(
      UserContext user, @PathVariable String share, @PathVariable String recipient) {
    grants.remove(shares.requireOwned(share, user), recipients.require(recipient));
    return ResponseEntity.noContent().build();
  }
}
