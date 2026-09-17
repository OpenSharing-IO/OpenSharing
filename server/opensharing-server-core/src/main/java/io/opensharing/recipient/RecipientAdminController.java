package io.opensharing.recipient;

import io.opensharing.auth.UserContext;
import io.opensharing.config.OpenSharingProperties;
import io.opensharing.http.ListResponse;
import jakarta.validation.Valid;
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

/** Provider HTTP API for recipient CRUD. */
@RestController
@RequestMapping("${opensharing.provider.base-path}/recipients")
public class RecipientAdminController {

  private final RecipientStore recipients;
  private final OpenSharingProperties properties;

  public RecipientAdminController(RecipientStore recipients, OpenSharingProperties properties) {
    this.recipients = recipients;
    this.properties = properties;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public RecipientResponse create(
      UserContext user, @Valid @RequestBody CreateRecipientRequest request) {
    RecipientStore.CreatedRecipient created =
        recipients.create(user, request.name(), request.comment(), request.authenticationType());
    return RecipientResponse.from(created.recipient(), activationUrl(created.activationCode()));
  }

  @GetMapping
  public ListResponse<RecipientResponse> list(UserContext user) {
    return ListResponse.of(
        recipients.list(Pageable.unpaged()).stream().map(RecipientResponse::from).toList());
  }

  @GetMapping("/{recipient}")
  public RecipientResponse get(UserContext user, @PathVariable String recipient) {
    return RecipientResponse.from(recipients.require(recipient));
  }

  @PatchMapping("/{recipient}")
  public RecipientResponse update(
      UserContext user,
      @PathVariable String recipient,
      @Valid @RequestBody UpdateRecipientRequest request) {
    return RecipientResponse.from(recipients.update(user, recipient, request.comment()));
  }

  @DeleteMapping("/{recipient}")
  public ResponseEntity<Void> delete(UserContext user, @PathVariable String recipient) {
    recipients.delete(recipient, user);
    return ResponseEntity.noContent().build();
  }

  private String activationUrl(String code) {
    return ServletUriComponentsBuilder.fromCurrentContextPath()
        .path(properties.getActivationPrefix())
        .path("/")
        .path(code)
        .toUriString();
  }
}
