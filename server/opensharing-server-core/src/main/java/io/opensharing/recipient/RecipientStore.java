package io.opensharing.recipient;

import io.opensharing.ObjectNames;
import io.opensharing.http.ApiException;
import io.opensharing.principal.Caller;
import io.opensharing.principal.Ownership;
import io.opensharing.share.ShareStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storage for recipients. Name lookups are case-insensitive, as the protocol requires; the token
 * lifecycle after creation belongs to {@link RecipientTokenService}.
 */
@Service
@Transactional
public class RecipientStore {

  private final RecipientRepository recipients;
  private final RecipientTokenRepository tokens;
  private final RecipientTokenService tokenService;
  private final ShareStore shares;

  public RecipientStore(
      RecipientRepository recipients,
      RecipientTokenRepository tokens,
      RecipientTokenService tokenService,
      ShareStore shares) {
    this.recipients = recipients;
    this.tokens = tokens;
    this.tokenService = tokenService;
    this.shares = shares;
  }

  /** A recipient and the one-time URL that reveals the token it was created with. */
  public record NewRecipient(
      RecipientEntity recipient, RecipientTokenService.IssuedToken token) {}

  /**
   * Creates a recipient together with its first token, so a recipient never exists without a way to
   * authenticate. Both writes commit together; replacing that token later is a rotation.
   *
   * @param tokenExpiresAt when the first token expires, or null for the configured default
   */
  public NewRecipient create(
      Caller author,
      String name,
      AuthType authType,
      List<String> ipAccessList,
      Map<String, String> properties,
      Instant tokenExpiresAt) {
    ObjectNames.validateRecipientName(name);
    if (authType == AuthType.OIDC) {
      throw ApiException.invalidParameter("auth_type OIDC is not implemented yet");
    }
    if (recipients.existsByNameLower(ObjectNames.normalize(name))) {
      throw ApiException.alreadyExists("recipient '" + name + "' already exists");
    }
    RecipientEntity recipient = new RecipientEntity();
    recipient.setName(name);
    recipient.setAuthType(authType);
    recipient.setIpAccessList(IpAccessList.validate(ipAccessList));
    recipient.setProperties(properties);
    recipient.setOwnerId(author.principalId());
    recipient.setCreatedBy(author.principalId());
    recipient.setUpdatedBy(author.principalId());
    recipients.save(recipient);
    return new NewRecipient(recipient, tokenService.issue(recipient, author, tokenExpiresAt));
  }

  /** Only non-null fields are applied. */
  public RecipientEntity update(
      Caller author, String name, List<String> ipAccessList, Map<String, String> properties) {
    RecipientEntity recipient = requireOwned(name, author);
    if (ipAccessList != null) {
      recipient.setIpAccessList(IpAccessList.validate(ipAccessList));
    }
    if (properties != null) {
      recipient.setProperties(properties);
    }
    recipient.setUpdatedBy(author.principalId());
    return recipients.save(recipient);
  }

  @Transactional(readOnly = true)
  public RecipientEntity require(String name) {
    return recipients
        .findByNameLower(ObjectNames.normalize(name))
        .orElseThrow(() -> ApiException.notFound("recipient '" + name + "' does not exist"));
  }

  /** Loads a recipient to be changed, which only its owner may do. */
  @Transactional(readOnly = true)
  public RecipientEntity requireOwned(String name, Caller caller) {
    RecipientEntity recipient = require(name);
    Ownership.requireOwner(
        recipient.getOwnerId(), caller, "recipient '" + recipient.getName() + "'");
    return recipient;
  }

  @Transactional(readOnly = true)
  public RecipientEntity requireById(String id) {
    return recipients
        .findById(id)
        .orElseThrow(() -> ApiException.unauthenticated("the recipient no longer exists"));
  }

  @Transactional(readOnly = true)
  public Page<RecipientEntity> list(Pageable pageable) {
    return recipients.findAllByOrderByNameLowerAsc(pageable);
  }

  /** Deleting a recipient revokes its tokens and every permission it held. */
  public void delete(String name, Caller caller) {
    RecipientEntity recipient = requireOwned(name, caller);
    tokens.deleteByRecipient(recipient);
    shares.revokeAll(recipient);
    recipients.delete(recipient);
  }
}
