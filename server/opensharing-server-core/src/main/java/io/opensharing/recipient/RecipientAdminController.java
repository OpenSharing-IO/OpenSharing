package io.opensharing.recipient;

import io.opensharing.http.ListResponse;
import io.opensharing.http.Listings;
import io.opensharing.principal.Caller;
import io.opensharing.share.SharePermissionResponse;
import io.opensharing.share.ShareStore;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Provider-admin API for recipients, their bearer tokens, and the shares they can read. */
@RestController
@RequestMapping("${opensharing.provider.base-path}/recipients")
public class RecipientAdminController {

  private final RecipientStore recipients;
  private final RecipientTokenService tokenService;
  private final ShareStore shares;
  private final Listings listings;

  public RecipientAdminController(
      RecipientStore recipients,
      RecipientTokenService tokenService,
      ShareStore shares,
      Listings listings) {
    this.recipients = recipients;
    this.tokenService = tokenService;
    this.shares = shares;
    this.listings = listings;
  }

  /** Creating a recipient mints its token; the activation URL comes back with it, once. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public CreatedRecipientResponse create(
      Caller caller, @Valid @RequestBody CreateRecipientRequest request) {
    return CreatedRecipientResponse.from(
        recipients.create(
            caller,
            request.name(),
            request.authType(),
            request.ipAccessList(),
            request.properties(),
            request.tokenExpiresAt()));
  }

  @GetMapping
  public ListResponse<RecipientResponse> list(
      @RequestParam(required = false) Integer maxResults,
      @RequestParam(required = false) String pageToken) {
    return listings.page(maxResults, pageToken, recipients::list, RecipientResponse::from);
  }

  @GetMapping("/{recipient}")
  public RecipientResponse get(@PathVariable String recipient) {
    return withTokens(recipients.require(recipient));
  }

  @PatchMapping("/{recipient}")
  public RecipientResponse update(
      Caller caller,
      @PathVariable String recipient,
      @RequestBody UpdateRecipientRequest request) {
    return withTokens(
        recipients.update(caller, recipient, request.ipAccessList(), request.properties()));
  }

  @DeleteMapping("/{recipient}")
  public ResponseEntity<Void> delete(Caller caller, @PathVariable String recipient) {
    recipients.delete(recipient, caller);
    return ResponseEntity.noContent().build();
  }

  /**
   * Replaces the recipient's token. The one it supersedes keeps working for its grace window, so the
   * recipient can install the new profile file before the old token stops working.
   */
  @PostMapping("/{recipient}/rotate-token")
  @ResponseStatus(HttpStatus.CREATED)
  public IssuedTokenResponse rotateToken(
      Caller caller,
      @PathVariable String recipient,
      @RequestBody(required = false) RotateTokenRequest request) {
    RotateTokenRequest effective = request == null ? RotateTokenRequest.DEFAULTS : request;
    RecipientTokenService.IssuedToken issued =
        tokenService.rotate(
            recipients.requireOwned(recipient, caller),
            caller,
            effective.expiresAt(),
            effective.grace());
    return IssuedTokenResponse.from(issued.token(), issued.activationUrl());
  }

  @GetMapping("/{recipient}/share-permissions")
  public ListResponse<SharePermissionResponse> listSharePermissions(
      @PathVariable String recipient) {
    List<SharePermissionResponse> items =
        shares.listPermissionsOf(recipients.require(recipient)).stream()
            .map(SharePermissionResponse::from)
            .toList();
    return ListResponse.of(items, null);
  }

  private RecipientResponse withTokens(RecipientEntity recipient) {
    return RecipientResponse.withTokens(
        recipient,
        tokenService.listTokens(recipient).stream().map(TokenResponse::from).toList());
  }
}
