package io.opensharing.recipient;

import io.opensharing.protocol.ProfileFile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves one-time activation URLs. The nonce in the path is the credential, so this endpoint is
 * intentionally not behind the admin or recipient token, and it works exactly once.
 */
@RestController
@RequestMapping("${opensharing.activation.base-path}")
public class ActivationController {

  private final RecipientTokenService tokenService;

  public ActivationController(RecipientTokenService tokenService) {
    this.tokenService = tokenService;
  }

  @GetMapping("/{nonce}")
  public ResponseEntity<ProfileFile> activate(@PathVariable String nonce) {
    ProfileFile profile = tokenService.activate(nonce);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"config.share\"")
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(profile);
  }
}
