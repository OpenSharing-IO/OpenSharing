package io.opensharing.recipient;

import io.opensharing.ObjectNames;
import io.opensharing.auth.UserContext;
import io.opensharing.config.OpenSharingProperties;
import io.opensharing.http.ApiException;
import io.opensharing.http.ListResponse;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
    AuthenticationType mode =
        request.authenticationType() == null
            ? AuthenticationType.TOKEN
            : request.authenticationType();
    if (mode != AuthenticationType.TOKEN) {
      throw ApiException.invalidParameter("authenticationType " + mode + " is not supported yet");
    }
    String activationCode = UUID.randomUUID().toString();
    RecipientEntity recipient =
        recipients.create(
            user,
            ObjectNames.validateRecipientName(request.name()),
            request.comment(),
            mode,
            sha256(activationCode));
    return RecipientResponse.from(recipient, activationUrl(activationCode));
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

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
