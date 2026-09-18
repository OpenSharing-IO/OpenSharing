package io.opensharing.recipient;

import io.opensharing.auth.UserContext;
import io.opensharing.config.OpenSharingProperties;
import io.opensharing.http.ListResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
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
  public RecipientResponse create(
      UserContext user, @Valid @RequestBody CreateRecipientRequest request) {
    if (request.authenticationType() != AuthenticationType.TOKEN) {
      throw ApiException.invalidParameter(
          "authenticationType " + request.authenticationType() + " is not supported yet");
    }
    Instant now = Instant.now();
    Instant expiresAt =
        request.tokenExpirationDays() == null
            ? plus(now, properties.getRecipientTokens().getDefaultTtl())
            : plus(now, Duration.ofDays(request.tokenExpirationDays()));
    requireFuture(expiresAt, now);
    RecipientEntity recipient =
        recipients.create(
            user,
            ObjectNames.validateRecipientName(request.name()),
            request.comment(),
            request.authenticationType(),
            UUID.randomUUID().toString(),
            expiresAt);
    return toResponse(recipient);
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

  @PostMapping("/{recipient}/rotate-token")
  @ResponseStatus(HttpStatus.CREATED)
  public IssuedTokenResponse rotateToken(
      UserContext user,
      @PathVariable String recipient,
      @Valid @RequestBody(required = false) RotateTokenRequest request) {
    RotateTokenRequest effective = request == null ? RotateTokenRequest.DEFAULTS : request;
    Instant now = Instant.now();
    Instant expiresAt =
        effective.tokenExpirationDays() == null
            ? plus(now, properties.getRecipientTokens().getDefaultTtl())
            : plus(now, Duration.ofDays(effective.tokenExpirationDays()));
    Duration grace =
        effective.existingTokenExpireInSeconds() == null
            ? properties.getRecipientTokens().getRotationGrace()
            : Duration.ofSeconds(effective.existingTokenExpireInSeconds());
    requireFuture(expiresAt, now);
    RecipientTokenEntity token =
        recipients.rotate(
            recipients.requireOwned(recipient, user),
            UUID.randomUUID().toString(),
            expiresAt,
            now,
            grace);
    return IssuedTokenResponse.from(token, activationUrl(token.getActivationCode()));
  }

  private RecipientResponse toResponse(RecipientEntity recipient) {
    String code = recipients.findActivationCode(recipient);
    return RecipientResponse.from(recipient, code == null ? null : activationUrl(code));
  }

  // Absolute URL on this server, so the recipient can open it as is.
  private String activationUrl(String code) {
    return ServletUriComponentsBuilder.fromCurrentContextPath()
        .path(properties.getActivationPrefix())
        .toUriString();
  }

  private static Instant plus(Instant now, Duration duration) {
    return duration == null ? null : now.plus(duration);
  }

  private static void requireFuture(Instant expiresAt, Instant now) {
    if (expiresAt != null && !expiresAt.isAfter(now)) {
      throw ApiException.invalidParameter("token expiration must be in the future");
    }
  }
}
