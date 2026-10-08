package io.opensharing.recipient;

import io.opensharing.auth.UserContext;
import io.opensharing.config.OpenSharingProperties;
import io.opensharing.http.ListResponse;
import io.opensharing.runtime.OpenSharing;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Provider HTTP API for recipient CRUD. Callers are authenticated by {@code
 * ProviderAuthenticationFilter}; any caller may read recipients, only the owner may change them.
 */
@RestController
@RequestMapping("${opensharing.provider.base-path}/recipients")
public class RecipientAdminController {

  private final RecipientService recipients;
  private final OpenSharingProperties properties;

  public RecipientAdminController(OpenSharing openSharing, OpenSharingProperties properties) {
    this.recipients = openSharing.recipients();
    this.properties = properties;
  }

  /**
   * {@code POST /recipients}: creates a recipient owned by the caller, with a new activation code.
   * Needs CREATE_RECIPIENT.
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public RecipientResponse create(UserContext user, @RequestBody CreateRecipientRequest request) {
    return recipients.create(user, request, activationBaseUrl());
  }

  /** {@code GET /recipients}: lists every recipient by name, unpaged. */
  @GetMapping
  public ListResponse<RecipientResponse> list(UserContext user) {
    return recipients.list(activationBaseUrl());
  }

  /** {@code GET /recipients/{recipient}}: gets a recipient by name in any case. */
  @GetMapping("/{recipient}")
  public RecipientResponse get(UserContext user, @PathVariable String recipient) {
    return recipients.get(recipient, activationBaseUrl());
  }

  /** {@code PATCH /recipients/{recipient}}: updates the fields set in the body. Owner only. */
  @PatchMapping("/{recipient}")
  public RecipientResponse update(
      UserContext user,
      @PathVariable String recipient,
      @RequestBody UpdateRecipientRequest request) {
    return recipients.update(user, recipient, request, activationBaseUrl());
  }

  /** {@code DELETE /recipients/{recipient}}: deletes the recipient and its tokens. Owner only. */
  @DeleteMapping("/{recipient}")
  public ResponseEntity<Void> delete(UserContext user, @PathVariable String recipient) {
    recipients.delete(user, recipient);
    return ResponseEntity.noContent().build();
  }

  /**
   * {@code POST /recipients/{recipient}/rotate-token}: issues a replacement token with a new
   * activation URL. Existing tokens stop working after the grace window. Owner only.
   */
  @PostMapping("/{recipient}/rotate-token")
  @ResponseStatus(HttpStatus.CREATED)
  public IssuedTokenResponse rotateToken(
      UserContext user,
      @PathVariable String recipient,
      @RequestBody(required = false) RotateTokenRequest request) {
    return recipients.rotateToken(user, recipient, request, activationBaseUrl());
  }

  // Absolute URL on this server, so the recipient can open the link as is.
  private String activationBaseUrl() {
    return ServletUriComponentsBuilder.fromCurrentContextPath()
        .path(properties.getActivationPrefix())
        .toUriString();
  }
}
