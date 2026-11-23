package io.opensharing.asset.table;

import io.opensharing.asset.SharedDataObjectStore;
import io.opensharing.asset.share.ProtocolShares;
import io.opensharing.http.ApiException;
import io.opensharing.http.ListResponse;
import io.opensharing.http.Pagination;
import io.opensharing.recipient.RecipientPrincipal;
import io.opensharing.share.ShareEntity;
import java.util.function.Function;

/**
 * What a recipient reads through the Iceberg REST catalog of a share it holds SELECT on: the
 * share's schemas as namespaces. Missing shares and schemas are reported as not found, as on the
 * protocol's own endpoints.
 *
 * <p>Callers pass the share name and schema alias normalized.
 */
public class ProtocolIcebergCatalog {

  private final ProtocolShares protocolShares;
  private final SharedDataObjectStore sharedObjects;

  public ProtocolIcebergCatalog(
      ProtocolShares protocolShares, SharedDataObjectStore sharedObjects) {
    this.protocolShares = protocolShares;
    this.sharedObjects = sharedObjects;
  }

  /** The share, when the recipient holds SELECT on it; otherwise fails with not-found. */
  public ShareEntity getShare(RecipientPrincipal recipient, String shareName) {
    return protocolShares.requireGranted(recipient, shareName);
  }

  /** One page of the share's schema aliases, by name. */
  public ListResponse<String> listNamespaces(
      ShareEntity share, Integer pageSize, String pageToken) {
    return Pagination.page(
        pageSize,
        pageToken,
        (offset, limit) -> sharedObjects.listSchemas(share, offset, limit),
        Function.identity());
  }

  /**
   * The namespace {@code schemaAlias} names, when the share has an object under it; otherwise fails
   * with not-found.
   */
  public String getNamespace(ShareEntity share, String schemaAlias) {
    if (sharedObjects.listInSchema(share, schemaAlias).isEmpty()) {
      throw ApiException.notFound(
          "namespace '" + schemaAlias + "' does not exist in share '" + share.getName() + "'");
    }
    return schemaAlias;
  }
}
