package io.opensharing.asset.schema;

import io.opensharing.asset.SharedDataObjectStore;
import io.opensharing.asset.share.ProtocolShares;
import io.opensharing.http.ApiException;
import io.opensharing.http.ListResponse;
import io.opensharing.http.Pagination;
import io.opensharing.recipient.RecipientPrincipal;
import io.opensharing.share.ShareEntity;

/**
 * The schemas a recipient sees through the sharing protocol: the distinct schema aliases of the
 * objects in a share it holds SELECT on.
 *
 * <p>Callers pass the share name and schema alias normalized.
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

  /** Gets one schema of the share by its alias. */
  public SchemaResponse get(RecipientPrincipal recipient, String shareName, String schemaAlias) {
    ShareEntity share = protocolShares.requireGranted(recipient, shareName);
    if (sharedObjects.listInSchema(share, schemaAlias).isEmpty()) {
      throw ApiException.notFound(
          "schema '" + schemaAlias + "' does not exist in share '" + share.getName() + "'");
    }
    return new SchemaResponse(schemaAlias, share.getName());
  }
}
