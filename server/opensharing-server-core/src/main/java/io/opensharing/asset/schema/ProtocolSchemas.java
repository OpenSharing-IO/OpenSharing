package io.opensharing.asset.schema;

import io.opensharing.asset.SharedDataObjectStore;
import io.opensharing.asset.share.ProtocolShares;
import io.opensharing.http.ListResponse;
import io.opensharing.recipient.RecipientPrincipal;
import io.opensharing.share.ShareEntity;
import io.opensharing.util.Pagination;

/**
 * The schemas a recipient sees through the sharing protocol: the distinct schema aliases of the
 * objects in a share it holds SELECT on.
 *
 * <p>Callers pass the share name normalized.
 */
public class ProtocolSchemas {

  private final ProtocolShares protocolShares;
  private final SharedDataObjectStore sharedObjects;

  public ProtocolSchemas(ProtocolShares protocolShares, SharedDataObjectStore sharedObjects) {
    this.protocolShares = protocolShares;
    this.sharedObjects = sharedObjects;
  }

  /** Lists one page of the share's schemas by schema alias. */
  public ListResponse<SchemaResponse> list(
      RecipientPrincipal recipient, String shareName, Integer maxResults, String pageToken) {
    ShareEntity share = protocolShares.requireGranted(recipient, shareName);
    return Pagination.protocolPage(
        maxResults,
        pageToken,
        (offset, limit) -> sharedObjects.listSchemas(share, offset, limit),
        schemaAlias -> new SchemaResponse(schemaAlias, share.getName()));
  }
}
