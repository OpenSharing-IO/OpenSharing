package io.opensharing.recipient;

import io.opensharing.config.OpenSharingProperties;
import io.opensharing.runtime.OpenSharing;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Public one-time redemption of a recipient activation URL. Not behind the provider filter: the
 * activation code itself is the credential.
 */
@RestController
@RequestMapping("${opensharing.activation-prefix}")
public class ActivationController {

  private final RecipientService recipients;
  private final OpenSharingProperties properties;

  public ActivationController(OpenSharing openSharing, OpenSharingProperties properties) {
    this.recipients = openSharing.recipients();
    this.properties = properties;
  }

  /**
   * {@code GET /activations/{code}}: redeems the code and downloads the recipient's profile file.
   * Fails with not-found when the code is unknown, already redeemed, or expired. The response is
   * not cacheable because it carries the bearer token.
   */
  @GetMapping("/{code}")
  public ResponseEntity<ProfileFile> activate(@PathVariable String code) {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"config.share\"")
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(recipients.activate(code, endpoint()));
  }

  // Absolute protocol URL on this server, which clients call with the bearer token.
  private String endpoint() {
    return ServletUriComponentsBuilder.fromCurrentContextPath()
        .path(properties.getProtocolPrefix())
        .toUriString();
  }
}
