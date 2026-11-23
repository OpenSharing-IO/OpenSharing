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
import io.opensharing.catalog.ResolveOptions;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.catalog.TableProperties;
import io.opensharing.exception.CatalogException;
import io.opensharing.http.ApiException;
import io.opensharing.http.ApiJson;
import io.opensharing.http.ListResponse;
import io.opensharing.http.Pagination;
import io.opensharing.recipient.RecipientPrincipal;
import io.opensharing.share.ShareEntity;
import java.io.IOException;
import java.util.ArrayList;
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
   * The current metadata of an Iceberg table of the share, and read credentials for its location
   * when {@code withCredentials}, as the catalog reports them to the share's owner in one {@code
   * resolveAsset} call. A table of another format is reported as missing, since the Iceberg catalog
   * has no such table.
   */
  public IcebergTable loadTable(
      RecipientPrincipal recipient,
      String shareName,
      String schemaAlias,
      String tableAlias,
      boolean withCredentials) {
    ShareEntity share = protocolShares.requireGranted(recipient, shareName);
    ResolvedAsset table =
        protocolTables.resolveTable(
            share,
            schemaAlias,
            tableAlias,
            withCredentials ? ResolveOptions.WITH_READ_CREDENTIALS : ResolveOptions.DEFAULT);
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
    return new IcebergTable(
        metadataLocation,
        parseMetadataJson(table, properties.metadata()),
        withCredentials
            ? requireCredentialsCoveringRoot(table, properties.readCredentials(), root)
            : List.of());
  }

  /**
   * Read credentials for the location of an Iceberg table of the share, for a client that already
   * has its metadata or is refreshing credentials that expire. One {@code getStorageCredentials}
   * call, identified by the share's recorded name and id; the catalog supplies the location.
   */
  public List<IcebergStorageCredential> loadCredentials(
      RecipientPrincipal recipient, String shareName, String schemaAlias, String tableAlias) {
    ShareEntity share = protocolShares.requireGranted(recipient, shareName);
    SharedDataObjectEntity sharedTable = requireSharedIcebergTable(share, schemaAlias, tableAlias);
    return orderCredentials(
        sharedTable.getFullName(),
        protocolTables.readCredentialsForSharedTable(share, sharedTable),
        sharedTable.getStorageLocation(),
        false);
  }

  /** Fails with not-found unless the share has an Iceberg table under the aliases. */
  public void requireTable(
      RecipientPrincipal recipient, String shareName, String schemaAlias, String tableAlias) {
    ShareEntity share = protocolShares.requireGranted(recipient, shareName);
    requireSharedIcebergTable(share, schemaAlias, tableAlias);
  }

  /** The table of the share under the aliases, which the share recorded as an Iceberg table. */
  private SharedDataObjectEntity requireSharedIcebergTable(
      ShareEntity share, String schemaAlias, String tableAlias) {
    SharedDataObjectEntity sharedTable =
        sharedObjects
            .findByAlias(share, schemaAlias, tableAlias)
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "table '"
                            + schemaAlias
                            + "."
                            + tableAlias
                            + "' does not exist in share '"
                            + share.getName()
                            + "'"));
    // This check avoids a catalog call; loadTable resolves the table.
    if (!isIcebergTable(sharedTable)) {
      throw ApiException.notFound(
          "table '" + schemaAlias + "." + tableAlias + "' is not an Iceberg table");
    }
    return sharedTable;
  }

  /** /** Whether the share recorded {@code sharedObject} as an Iceberg table. */
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

  /** The most specific covering credential first, then the rest in catalog order. */
  static List<IcebergStorageCredential> requireCredentialsCoveringRoot(
      ResolvedAsset table, List<StorageCredentials> readCredentials, String root) {
    return orderCredentials(table.fullName(), readCredentials, root, true);
  }

  private static List<IcebergStorageCredential> orderCredentials(
      String tableName, List<StorageCredentials> readCredentials, String root, boolean required) {
    if (readCredentials.isEmpty()) {
      throw new CatalogException(
          "catalog returned no read credentials for table '" + tableName + "'");
    }
    String normalizedRoot =
        root == null || root.isBlank() ? null : StorageLocations.icebergPath(root);
    List<IcebergStorageCredential> credentials = new ArrayList<>();
    int covering = -1;
    int longest = -1;
    for (StorageCredentials candidate : readCredentials) {
      String prefix = StorageLocations.icebergCredentialPath(candidate.prefix());
      credentials.add(IcebergStorageCredential.from(candidate));
      if (normalizedRoot != null
          && StorageLocations.covers(prefix, normalizedRoot)
          && prefix.length() > longest) {
        covering = credentials.size() - 1;
        longest = prefix.length();
      }
    }
    if (covering < 0 && required) {
      throw new CatalogException(
          "catalog returned no read credentials covering '"
              + root
              + "' for table '"
              + tableName
              + "'");
    }
    if (covering >= 0) {
      credentials.add(0, credentials.remove(covering));
    }
    return List.copyOf(credentials);
  }
}
