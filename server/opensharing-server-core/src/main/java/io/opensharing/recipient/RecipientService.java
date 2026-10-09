package io.opensharing.recipient;

import io.opensharing.ObjectNames;
import io.opensharing.auth.TokenHashes;
import io.opensharing.auth.UserContext;
import io.opensharing.http.ApiException;
import io.opensharing.http.ListResponse;
import io.opensharing.http.Pagination;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;

/**
 * Provider recipient CRUD and token issuance. The host authenticates the caller and checks
 * CREATE_RECIPIENT before {@link #create}; any caller may read recipients, only the owner may
 * change them. {@link #activate} is public: the activation code itself is the credential.
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

  private final RecipientStore recipients;
  private final RecipientTokenSettings tokens;
  private final Pagination pagination;
  private final SecureRandom random = new SecureRandom();

  public RecipientService(
      RecipientStore recipients, RecipientTokenSettings tokens, Pagination pagination) {
    this.recipients = recipients;
    this.tokens = tokens;
    this.pagination = pagination;
  }

  /** Creates a recipient owned by {@code user}, with a new activation code. */
  public RecipientResponse create(
      UserContext user, CreateRecipientRequest request, String activationBaseUrl) {
    if (request.authenticationType() == null) {
      throw ApiException.invalidParameter("authenticationType is required");
    }
    if (request.authenticationType() != AuthenticationType.TOKEN) {
      throw ApiException.invalidParameter(
          "authenticationType " + request.authenticationType() + " is not supported yet");
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

  /** Lists one page of recipients by name. */
  public ListResponse<RecipientResponse> list(
      Integer maxResults, String pageToken, String activationBaseUrl) {
    return pagination.page(
        maxResults, pageToken, recipients::list, r -> toResponse(r, activationBaseUrl));
  }

  /** Gets a recipient by name in any case. */
  public RecipientResponse get(String recipient, String activationBaseUrl) {
    return toResponse(recipients.require(recipient), activationBaseUrl);
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
   * grace window. A null {@code request} uses the defaults. Owner only.
   */
  public IssuedTokenResponse rotateToken(
      UserContext user, String recipient, RotateTokenRequest request, String activationBaseUrl) {
    RotateTokenRequest effective = request == null ? RotateTokenRequest.DEFAULTS : request;
    if (effective.existingTokenExpireInSeconds() != null
        && effective.existingTokenExpireInSeconds() < 0) {
      throw ApiException.invalidParameter("existingTokenExpireInSeconds must not be negative");
    }
    Instant now = Instant.now();
    Instant expiresAt = expiresAt(effective.tokenExpirationDays(), now);
    Duration grace =
        effective.existingTokenExpireInSeconds() == null
            ? tokens.rotationGrace()
            : Duration.ofSeconds(effective.existingTokenExpireInSeconds());
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
        SHARE_CREDENTIALS_VERSION,
        bearer,
        endpoint,
        token.getExpiresAt() == null ? null : EXPIRATION_TIME.format(token.getExpiresAt()));
  }

  // The request's lifetime in days, else the configured TTL; null means the token never expires.
  private Instant expiresAt(Long days, Instant now) {
    if (days != null && days <= 0) {
      throw ApiException.invalidParameter("tokenExpirationDays must be positive");
    }
    Duration ttl = days == null ? tokens.defaultTtl() : Duration.ofDays(days);
    if (ttl == null) {
      return null;
    }
    Instant expiresAt = now.plus(ttl);
    if (!expiresAt.isAfter(now)) {
      throw ApiException.invalidParameter("token expiration must be in the future");
    }
    return expiresAt;
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
