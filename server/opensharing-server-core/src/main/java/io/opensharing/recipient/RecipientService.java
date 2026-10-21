package io.opensharing.recipient;

import io.opensharing.ObjectNames;
import io.opensharing.auth.AuthContext;
import io.opensharing.auth.Privilege;
import io.opensharing.auth.TokenHashes;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.http.ApiException;
import io.opensharing.http.ListResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;

/**
 * Provider recipient CRUD and token issuance for a caller the host has authenticated. Creating a
 * recipient needs CREATE_RECIPIENT from the catalog, and only the owner may change it. Only the
 * owner and callers with USE_RECIPIENT, who may read anyone's recipients, are meant to list and get
 * recipients. {@link #activate} is public: the activation code itself is the credential.
 *
 * <p>{@code activationBaseUrl} is the absolute URL recipient activation links are built under, such
 * as {@code https://host/api/1.0/opensharing/activations}; the host knows its own address.
 */
public class RecipientService {

  /** Profile file format version defined by the Delta Sharing protocol. */
  private static final int SHARE_CREDENTIALS_VERSION = 1;

  /** Random bytes per bearer token; 64 characters once base64url-encoded. */
  private static final int BEARER_BYTES = 48;

  /** Profile {@code expirationTime} format, as in {@code 2026-01-01T00:00:00.000Z}. */
  private static final DateTimeFormatter EXPIRATION_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneOffset.UTC);

  private final CatalogConnector catalog;
  private final RecipientStore recipients;
  private final SecureRandom random = new SecureRandom();

  public RecipientService(CatalogConnector catalog, RecipientStore recipients) {
    this.catalog = catalog;
    this.recipients = recipients;
  }

  /**
   * Creates a recipient owned by the caller, who needs CREATE_RECIPIENT from the catalog, with a
   * new activation code.
   */
  public RecipientResponse create(
      UserContext user, CreateRecipientRequest request, String activationBaseUrl) {
    // The host has already authenticated the caller. A host that also checks CREATE_RECIPIENT at
    // its endpoint can make this a no-op.
    catalog.authorize(AuthContext.of(user), Privilege.CREATE_RECIPIENT);
    if (request.authenticationType() == null) {
      throw ApiException.invalidParameter("authenticationType is required");
    }
    Instant now = Instant.now();
    Instant expiresAt = expiresAt(request.tokenExpirationDays(), now);
    RecipientEntity recipient =
        recipients.create(
            user,
            ObjectNames.validateRecipientName(request.name()),
            request.comment(),
            request.authenticationType(),
            UUID.randomUUID().toString(),
            expiresAt);
    return toResponse(recipient, activationBaseUrl);
  }

  /** Lists every recipient by name, unpaged. */
  public ListResponse<RecipientResponse> list(String activationBaseUrl) {
    // TODO: page with maxResults and pageToken.
    // TODO: introduce USE_RECIPIENT and list only the caller's recipients unless they hold it.
    return ListResponse.of(
        recipients.list().stream().map(r -> toResponse(r, activationBaseUrl)).toList());
  }

  /** Gets a recipient by name in any case. */
  public RecipientResponse get(String recipient, String activationBaseUrl) {
    // TODO: introduce USE_RECIPIENT and get only the caller's recipient unless they hold it.
    return toResponse(recipients.getOrThrow(recipient), activationBaseUrl);
  }

  /** Updates the fields set in {@code request}. Owner only. */
  public RecipientResponse update(
      UserContext user,
      String recipient,
      UpdateRecipientRequest request,
      String activationBaseUrl) {
    return toResponse(recipients.update(user, recipient, request.comment()), activationBaseUrl);
  }

  /** Deletes the recipient, its tokens and its permissions. Owner only. */
  public void delete(UserContext user, String recipient) {
    recipients.delete(recipient, user);
  }

  /**
   * Issues a replacement token with a new activation URL. Existing tokens stop working after the
   * grace window, immediately unless the request sets one. Owner only.
   */
  public IssuedTokenResponse rotateToken(
      UserContext user, String recipient, RotateTokenRequest request, String activationBaseUrl) {
    Long graceSeconds = request.existingTokenExpireInSeconds();
    if (graceSeconds != null && graceSeconds < 0) {
      throw ApiException.invalidParameter("existingTokenExpireInSeconds must not be negative");
    }
    Instant now = Instant.now();
    Instant expiresAt = expiresAt(request.tokenExpirationDays(), now);
    Duration grace = graceSeconds == null ? Duration.ZERO : Duration.ofSeconds(graceSeconds);
    RecipientTokenEntity token =
        recipients.rotate(user, recipient, UUID.randomUUID().toString(), expiresAt, now, grace);
    return IssuedTokenResponse.from(token, activationBaseUrl + "/" + token.getActivationCode());
  }

  /**
   * Redeems the activation code into a profile file with a new bearer token, stored only as a
   * hash. Fails with not-found when the code is unknown, already redeemed, or expired. {@code
   * endpoint} is the absolute URL the host serves the sharing protocol under.
   */
  public ProfileFile activate(String code, String endpoint) {
    String bearer = newBearer();
    RecipientTokenEntity token =
        recipients.activate(code, TokenHashes.sha256(bearer), Instant.now());
    return new ProfileFile(
        SHARE_CREDENTIALS_VERSION, bearer, endpoint, EXPIRATION_TIME.format(token.getExpiresAt()));
  }

  private static Instant expiresAt(Long days, Instant now) {
    if (days == null) {
      throw ApiException.invalidParameter("tokenExpirationDays is required");
    }
    if (days <= 0) {
      throw ApiException.invalidParameter("tokenExpirationDays must be positive");
    }
    return now.plus(Duration.ofDays(days));
  }

  private RecipientResponse toResponse(RecipientEntity recipient, String activationBaseUrl) {
    String code = recipients.findActivationCode(recipient);
    return RecipientResponse.from(recipient, code == null ? null : activationBaseUrl + "/" + code);
  }

  private String newBearer() {
    byte[] bytes = new byte[BEARER_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
