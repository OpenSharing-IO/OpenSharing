package io.opensharing.recipient;

import io.opensharing.ObjectNames;
import io.opensharing.auth.UserContext;
import io.opensharing.http.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Storage for recipients. Names are stored lowercase and looked up case-insensitively. */
@Service
@Transactional
public class RecipientStore {

  private final RecipientRepository recipients;

  public RecipientStore(RecipientRepository recipients) {
    this.recipients = recipients;
  }

  public CreatedRecipient create(
      UserContext author, String name, String comment, AuthenticationType authenticationType) {
    String stored = ObjectNames.validateRecipientName(name);
    if (recipients.existsByName(stored)) {
      throw ApiException.alreadyExists("recipient '" + stored + "' already exists");
    }
    AuthenticationType mode =
        authenticationType == null ? AuthenticationType.TOKEN : authenticationType;
    if (mode != AuthenticationType.TOKEN) {
      throw ApiException.invalidParameter("authenticationType " + mode + " is not supported yet");
    }
    String activationCode = UUID.randomUUID().toString();
    RecipientEntity recipient = new RecipientEntity();
    recipient.setName(stored);
    recipient.setComment(comment);
    recipient.setOwnerId(author.id());
    recipient.setAuthenticationType(mode);
    recipient.setActivationCodeHash(sha256(activationCode));
    return new CreatedRecipient(recipients.save(recipient), activationCode);
  }

  public RecipientEntity update(UserContext user, String name, String comment) {
    RecipientEntity recipient = requireOwned(name, user);
    if (comment != null) {
      recipient.setComment(comment);
    }
    return recipients.save(recipient);
  }

  @Transactional(readOnly = true)
  public Optional<RecipientEntity> find(String name) {
    return recipients.findByName(ObjectNames.normalize(name));
  }

  @Transactional(readOnly = true)
  public RecipientEntity require(String name) {
    return find(name)
        .orElseThrow(() -> ApiException.notFound("recipient '" + name + "' does not exist"));
  }

  @Transactional(readOnly = true)
  public RecipientEntity requireOwned(String name, UserContext user) {
    RecipientEntity recipient = require(name);
    user.requireOwner(recipient.getOwnerId(), "recipient '" + recipient.getName() + "'");
    return recipient;
  }

  @Transactional(readOnly = true)
  public Page<RecipientEntity> list(Pageable pageable) {
    return recipients.findAllByOrderByNameAsc(pageable);
  }

  public void delete(String name, UserContext user) {
    RecipientEntity recipient = requireOwned(name, user);
    recipients.delete(recipient);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A newly created recipient and the one-time code that is only returned here. */
  public record CreatedRecipient(RecipientEntity recipient, String activationCode) {}
}
