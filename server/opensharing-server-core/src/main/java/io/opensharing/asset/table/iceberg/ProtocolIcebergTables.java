package io.opensharing.asset.table.iceberg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.asset.SharedDataObjectStore;
import io.opensharing.asset.share.ProtocolShares;
import io.opensharing.asset.table.ProtocolTables;
import io.opensharing.asset.table.StorageLocations;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.DataSourceFormat;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.TableProperties;
import io.opensharing.exception.CatalogException;
import io.opensharing.http.ApiException;
import io.opensharing.http.ApiJson;
import io.opensharing.http.ListResponse;
import io.opensharing.http.Pagination;
import io.opensharing.recipient.RecipientPrincipal;
import io.opensharing.share.ShareEntity;
import java.io.IOException;
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
  private final ProtocolTables protocolTables;

  public ProtocolIcebergTables(
      ProtocolShares protocolShares,
      SharedDataObjectStore sharedObjects,
      ProtocolTables protocolTables) {
    this.protocolShares = protocolShares;
    this.sharedObjects = sharedObjects;
    this.protocolTables = protocolTables;
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
    // Listing avoids catalog calls; loadTable resolves each table.
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

  /**
   * The current metadata of an Iceberg table of the share, as the catalog reports it to the share's
   * owner in one {@code resolveAsset} call. A table of another format is reported as missing, since
   * the Iceberg catalog has no such table.
   */
  public IcebergTable loadTable(
      RecipientPrincipal recipient, String shareName, String schemaAlias, String tableAlias) {
    ShareEntity share = protocolShares.requireGranted(recipient, shareName);
    ResolvedAsset table = protocolTables.resolveTable(share, schemaAlias, tableAlias);
    if (!(table.additionalProperties() instanceof TableProperties properties)
        || properties.dataSourceFormat() != DataSourceFormat.ICEBERG) {
      throw ApiException.notFound(
          "table '" + schemaAlias + "." + tableAlias + "' is not an Iceberg table");
    }
    String root = table.location() == null ? null : table.location().storageLocation();
    if (root == null || root.isBlank()) {
      throw new CatalogException(
          "catalog table '" + table.fullName() + "' has no storage location");
    }
    String metadataLocation = properties.metadataLocation();
    if (metadataLocation == null || metadataLocation.isBlank()) {
      throw new CatalogException(
          "catalog Iceberg table '" + table.fullName() + "' has no metadata location");
    }
    // A recipient reads only inside the table's location.
    if (!StorageLocations.covers(
        StorageLocations.icebergPath(root), StorageLocations.icebergPath(metadataLocation))) {
      throw new CatalogException(
          "catalog Iceberg table '"
              + table.fullName()
              + "' has metadata at '"
              + metadataLocation
              + "', outside its location '"
              + root
              + "'");
    }
    return new IcebergTable(metadataLocation, parseMetadataJson(table, properties.metadata()));
  }

  /** Whether the share recorded {@code sharedObject} as an Iceberg table. */
  private static boolean isIcebergTable(SharedDataObjectEntity sharedObject) {
    return sharedObject.getType() == AssetType.TABLE
        && sharedObject.getSourceFormat() == DataSourceFormat.ICEBERG;
  }

  /** The catalog's metadata JSON for {@code table}, which must be a JSON object. */
  private static ObjectNode parseMetadataJson(ResolvedAsset table, String metadata) {
    if (metadata == null) {
      throw new CatalogException(
          "catalog returned no Iceberg metadata for '" + table.fullName() + "'");
    }
    JsonNode parsed;
    try {
      parsed = ApiJson.MAPPER.readTree(metadata);
    } catch (IOException unreadable) {
      throw new CatalogException(
          "catalog returned Iceberg metadata for '" + table.fullName() + "' that is not JSON",
          unreadable);
    }
    if (!(parsed instanceof ObjectNode object)) {
      throw new CatalogException(
          "catalog returned Iceberg metadata for '"
              + table.fullName()
              + "' that is not a JSON object");
    }
    return object;
  }
}
