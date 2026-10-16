package io.opensharing.asset.schema;

import io.opensharing.asset.SharedDataObjectStore;
import io.opensharing.asset.share.ProtocolShares;
import io.opensharing.http.ListResponse;
import io.opensharing.http.Pagination;
import io.opensharing.recipient.RecipientPrincipal;
import io.opensharing.share.ShareEntity;

/**
 * The schemas a recipient sees through the sharing protocol: the distinct schema aliases of the
 * objects in a share it holds SELECT on.
 */
public class ProtocolSchemas {

  private final ProtocolShares shares;
  private final SharedDataObjectStore objects;

  public ProtocolSchemas(ProtocolShares shares, SharedDataObjectStore objects) {
    this.shares = shares;
    this.objects = objects;
  }

  /** Lists one page of the share's schemas by name. */
  public ListResponse<SchemaResponse> list(
      RecipientPrincipal recipient, String share, Integer maxResults, String pageToken) {
    ShareEntity granted = shares.requireGranted(recipient, share);
    return Pagination.page(
        maxResults,
        pageToken,
        (offset, limit) -> objects.listSchemas(granted, offset, limit),
        name -> new SchemaResponse(name, granted.getName()));
  }
}
