package io.opensharing.asset.share;

import io.opensharing.http.ApiException;
import io.opensharing.http.ListResponse;
import io.opensharing.http.Pagination;
import io.opensharing.recipient.RecipientPrincipal;
import io.opensharing.share.ShareEntity;
import io.opensharing.share.SharePermissionStore;
import io.opensharing.share.ShareResponse;
import io.opensharing.share.ShareStore;

/**
 * The shares a recipient sees through the sharing protocol: those it holds SELECT on. Any other
 * share is reported as missing, so recipients cannot learn which shares exist.
 *
 * <p>Callers pass the share name normalized.
 */
public class ProtocolShares {

  private final ShareStore shareStore;
  private final SharePermissionStore sharePermissions;

  public ProtocolShares(ShareStore shareStore, SharePermissionStore sharePermissions) {
    this.shareStore = shareStore;
    this.sharePermissions = sharePermissions;
  }

  /** Lists one page of the recipient's shares by share name. */
  public ListResponse<ShareResponse> list(
      RecipientPrincipal recipient, Integer maxResults, String pageToken) {
    return Pagination.protocolPage(
        maxResults,
        pageToken,
        (offset, limit) -> sharePermissions.listSharesFor(recipient.recipientId(), offset, limit),
        ShareResponse::from);
  }

  /** Gets one of the recipient's shares by share name. */
  public GetShareResponse get(RecipientPrincipal recipient, String shareName) {
    return new GetShareResponse(ShareResponse.from(requireGranted(recipient, shareName)));
  }

  /** Looks up a share by share name. Fails with not-found unless the recipient holds SELECT. */
  public ShareEntity requireGranted(RecipientPrincipal recipient, String shareName) {
    return shareStore
        .find(shareName)
        .filter(share -> sharePermissions.hasSelect(share, recipient.recipientId()))
        .orElseThrow(() -> ApiException.notFound("share '" + shareName + "' does not exist"));
  }
}
