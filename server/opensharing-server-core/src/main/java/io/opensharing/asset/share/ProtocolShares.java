package io.opensharing.asset.share;

import io.opensharing.http.ApiException;
import io.opensharing.http.ListResponse;
import io.opensharing.http.Pagination;
import io.opensharing.recipient.RecipientPrincipal;
import io.opensharing.share.ShareResponse;
import io.opensharing.share.SharePermissionStore;
import io.opensharing.share.ShareStore;

/**
 * The shares a recipient sees through the sharing protocol: those it holds SELECT on. Any other
 * share is reported as missing, so recipients cannot learn which shares exist.
 */
public class ProtocolShares {

  private final ShareStore shares;
  private final SharePermissionStore permissions;

  public ProtocolShares(ShareStore shares, SharePermissionStore permissions) {
    this.shares = shares;
    this.permissions = permissions;
  }

  /** Lists one page of the recipient's shares by name. */
  public ListResponse<ShareResponse> list(
      RecipientPrincipal recipient, Integer maxResults, String pageToken) {
    return Pagination.protocolPage(
        maxResults,
        pageToken,
        (offset, limit) -> permissions.listSharesFor(recipient.recipientId(), offset, limit),
        ShareResponse::from);
  }

  /** Gets one of the recipient's shares by name in any case. */
  public GetShareResponse get(RecipientPrincipal recipient, String share) {
    return shares
        .find(share)
        .filter(candidate -> permissions.hasSelect(candidate, recipient.recipientId()))
        .map(granted -> new GetShareResponse(ShareResponse.from(granted)))
        .orElseThrow(() -> ApiException.notFound("share '" + share + "' does not exist"));
  }
}
