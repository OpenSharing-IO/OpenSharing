package io.opensharing.share;

import io.opensharing.http.ApiException;
import io.opensharing.recipient.RecipientEntity;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Storage for which recipients can access a share. */
@Service
@Transactional
public class ShareRecipientStore {

  private final ShareRecipientRepository grants;

  public ShareRecipientStore(ShareRecipientRepository grants) {
    this.grants = grants;
  }

  public ShareRecipientEntity add(ShareEntity share, RecipientEntity recipient) {
    if (grants.existsByShareAndRecipient(share, recipient)) {
      throw ApiException.alreadyExists(
          "recipient '"
              + recipient.getName()
              + "' already has access to share '"
              + share.getName()
              + "'");
    }
    ShareRecipientEntity grant = new ShareRecipientEntity();
    grant.setShare(share);
    grant.setRecipient(recipient);
    return grants.save(grant);
  }

  @Transactional(readOnly = true)
  public List<ShareRecipientEntity> list(ShareEntity share) {
    return grants.findByShareOrderByRecipient_NameAsc(share);
  }

  public void remove(ShareEntity share, RecipientEntity recipient) {
    ShareRecipientEntity grant =
        grants
            .findByShareAndRecipient(share, recipient)
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "recipient '"
                            + recipient.getName()
                            + "' does not have access to share '"
                            + share.getName()
                            + "'"));
    grants.delete(grant);
  }
}
