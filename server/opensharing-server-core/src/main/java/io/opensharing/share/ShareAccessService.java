package io.opensharing.share;

import io.opensharing.http.ApiException;
import io.opensharing.recipient.RecipientEntity;
import io.opensharing.recipient.RecipientPrincipal;
import io.opensharing.recipient.RecipientStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authorization for protocol requests: a recipient sees a share only if it has been granted, and
 * everything inside a granted share.
 *
 * <p>An ungranted share is reported as missing rather than forbidden, so recipients cannot probe for
 * the names of shares belonging to others.
 */
@Service
@Transactional(readOnly = true)
public class ShareAccessService {

  private final ShareStore shares;
  private final RecipientStore recipients;

  public ShareAccessService(ShareStore shares, RecipientStore recipients) {
    this.shares = shares;
    this.recipients = recipients;
  }

  public RecipientEntity recipient(RecipientPrincipal principal) {
    return recipients.requireById(principal.recipientId());
  }

  public ShareEntity requireShare(RecipientPrincipal principal, String shareName) {
    RecipientEntity recipient = recipient(principal);
    return shares
        .find(shareName)
        .filter(candidate -> shares.isSharedWith(candidate, recipient))
        .orElseThrow(() -> ApiException.notFound("share '" + shareName + "' does not exist"));
  }
}
