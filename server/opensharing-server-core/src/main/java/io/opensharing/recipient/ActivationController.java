package io.opensharing.recipient;

import io.opensharing.config.OpenSharingProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Public one-time redemption of a recipient activation URL. */
@RestController
@RequestMapping("${opensharing.activation-prefix}")
public class ActivationController {

  private static final int SHARE_CREDENTIALS_VERSION = 1;

  private final RecipientStore recipients;
  private final OpenSharingProperties properties;

  public ActivationController(RecipientStore recipients, OpenSharingProperties properties) {
    this.recipients = recipients;
    this.properties = properties;
  }

  @GetMapping("/{code}")
  public ProfileFile activate(@PathVariable String code) {
    return new ProfileFile(SHARE_CREDENTIALS_VERSION, endpoint(), recipients.activate(code));
  }

  private String endpoint() {
    return ServletUriComponentsBuilder.fromCurrentContextPath()
        .path(properties.getProtocolPrefix())
        .toUriString();
  }
}
