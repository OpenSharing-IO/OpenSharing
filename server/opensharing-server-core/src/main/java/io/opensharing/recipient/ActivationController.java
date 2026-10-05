package io.opensharing.recipient;

import io.opensharing.auth.TokenHashes;
import io.opensharing.config.OpenSharingProperties;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
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
 * activation code itself is the credential. Each redeem issues a new bearer token, returned only
 * in the profile file and stored only as a hash.
 */
@RestController
@RequestMapping("${opensharing.activation-prefix}")
public class ActivationController {

  /** Profile file format version defined by the Delta Sharing protocol. */
  private static final int SHARE_CREDENTIALS_VERSION = 1;

  /** Random bytes per bearer token; 64 characters once base64url-encoded. */
  private static final int BEARER_BYTES = 48;

  /** Profile {@code expirationTime} format, as in {@code 2026-01-01T00:00:00.000Z}. */
  private static final DateTimeFormatter EXPIRATION_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneOffset.UTC);

  private final RecipientStore recipients;
  private final OpenSharingProperties properties;
  private final SecureRandom random = new SecureRandom();

  public ActivationController(RecipientStore recipients, OpenSharingProperties properties) {
    this.recipients = recipients;
    this.properties = properties;
  }

  /**
   * {@code GET /activations/{code}}: redeems the code and downloads the recipient's profile file.
   * Fails with not-found when the code is unknown, already redeemed, or expired. The response is
   * not cacheable because it carries the bearer token.
   */
  @GetMapping("/{code}")
  public ResponseEntity<ProfileFile> activate(@PathVariable String code) {
    String bearer = newBearer();
    RecipientTokenEntity token =
        recipients.activate(code, TokenHashes.sha256(bearer), Instant.now());
    ProfileFile profile =
        new ProfileFile(
            SHARE_CREDENTIALS_VERSION,
            bearer,
            endpoint(),
            token.getExpiresAt() == null ? null : EXPIRATION_TIME.format(token.getExpiresAt()));
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"config.share\"")
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(profile);
  }

  // Absolute protocol URL on this server, which clients call with the bearer token.
  private String endpoint() {
    return ServletUriComponentsBuilder.fromCurrentContextPath()
        .path(properties.getProtocolPrefix())
        .toUriString();
  }

  private String newBearer() {
    byte[] bytes = new byte[BEARER_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
