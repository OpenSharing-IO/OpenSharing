package io.opensharing.recipient;

import io.opensharing.ObjectNames;
import io.opensharing.auth.UserContext;
import io.opensharing.http.ApiException;
import java.util.Optional;
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

  public RecipientEntity create(UserContext author, String name, String comment) {
    String stored = ObjectNames.validateRecipientName(name);
    if (recipients.existsByName(stored)) {
      throw ApiException.alreadyExists("recipient '" + stored + "' already exists");
    }
    RecipientEntity recipient = new RecipientEntity();
    recipient.setName(stored);
    recipient.setComment(comment);
    recipient.setOwnerId(author.id());
    recipient.setCreatedBy(author.id());
    return recipients.save(recipient);
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
}
