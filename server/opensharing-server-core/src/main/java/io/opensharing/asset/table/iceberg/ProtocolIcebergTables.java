package io.opensharing.asset.table.iceberg;

import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.asset.SharedDataObjectStore;
import io.opensharing.asset.share.ProtocolShares;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.DataSourceFormat;
import io.opensharing.http.ApiException;
import io.opensharing.http.ListResponse;
import io.opensharing.http.Pagination;
import io.opensharing.recipient.RecipientPrincipal;
import io.opensharing.share.ShareEntity;
import java.util.List;
import java.util.function.Function;

/**
 * The Iceberg tables a recipient reads through the Iceberg REST catalog of a share it holds
 * SELECT on. A share it can't read, and missing schemas and tables, are reported as not found, as
 * on the protocol's own endpoints.
 *
 * <p>Callers pass the share name and the schema and table aliases normalized.
 */
public class ProtocolIcebergTables {

  private final ProtocolShares protocolShares;
  private final SharedDataObjectStore sharedObjects;

  public ProtocolIcebergTables(
      ProtocolShares protocolShares, SharedDataObjectStore sharedObjects) {
    this.protocolShares = protocolShares;
    this.sharedObjects = sharedObjects;
  }

  /**
   * The Iceberg table aliases the share has under {@code schemaAlias}, ordered by name. A schema
   * of only Delta tables has none, but one the share doesn't have is not found.
   */
  public List<String> listAllTables(
      RecipientPrincipal recipient, String shareName, String schemaAlias) {
    ShareEntity share = protocolShares.requireGranted(recipient, shareName);
    List<SharedDataObjectEntity> schemaObjects = sharedObjects.listInSchema(share, schemaAlias);
    if (schemaObjects.isEmpty()) {
      throw ApiException.notFound(
          "schema '" + schemaAlias + "' does not exist in share '" + share.getName() + "'");
    }
    return schemaObjects.stream()
        .filter(ProtocolIcebergTables::isIcebergTable)
        .map(SharedDataObjectEntity::getSharedAsTable)
        .toList();
  }

  /** One page of the share's Iceberg table aliases under {@code schemaAlias}. */
  public ListResponse<String> listTables(
      RecipientPrincipal recipient,
      String shareName,
      String schemaAlias,
      Integer pageSize,
      String pageToken) {
    List<String> tables = listAllTables(recipient, shareName, schemaAlias);
    return Pagination.page(
        pageSize,
        pageToken,
        (offset, limit) -> {
          int from = Math.min(offset, tables.size());
          return tables.subList(from, from + Math.min(limit, tables.size() - from));
        },
        Function.identity());
  }

  /** Whether the share recorded {@code sharedObject} as an Iceberg table. */
  private static boolean isIcebergTable(SharedDataObjectEntity sharedObject) {
    return sharedObject.getType() == AssetType.TABLE
        && sharedObject.getSourceFormat() == DataSourceFormat.ICEBERG;
  }
}
