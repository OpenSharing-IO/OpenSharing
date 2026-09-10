package io.opensharing.catalog.unity;

import java.util.List;

/**
 * The slice of the Unity Catalog REST API this connector speaks, as records to read its JSON into.
 * Only the fields the sharing server acts on are declared; Unity Catalog sends a good deal more with
 * every table, and the client is configured to ignore what is not named here so that a catalog
 * growing a field does not break a server reading it.
 *
 * <p>Every field is a string or a number even where the API documents an enum, so that a value this
 * build has not heard of — a new {@code DataSourceFormat}, a table type added next release — arrives
 * as an unrecognized string to be refused with an explanation, rather than as a parse failure that
 * only says the response was unreadable.
 */
final class UnityCatalogApi {

  private UnityCatalogApi() {}

  /** {@code GET /tables/{full_name}} and each element of a table listing. */
  record TableInfo(
      String name,
      String catalogName,
      String schemaName,
      String tableType,
      String dataSourceFormat,
      String storageLocation,
      String tableId,
      List<ColumnInfo> columns) {

    /** The name Unity Catalog itself would use for this table, when it stated all three parts. */
    String fullName() {
      if (isBlank(catalogName) || isBlank(schemaName) || isBlank(name)) {
        return null;
      }
      return catalogName + "." + schemaName + "." + name;
    }

    private static boolean isBlank(String value) {
      return value == null || value.isBlank();
    }
  }

  /**
   * @param partitionIndex the column's position in the partition spec, or null for a column the
   *     table is not partitioned by
   */
  record ColumnInfo(String name, Integer partitionIndex) {}

  /**
   * {@code GET /tables?catalog_name=&schema_name=}.
   *
   * @param nextPageToken absent or empty on the last page
   */
  record ListTablesResponse(List<TableInfo> tables, String nextPageToken) {}

  /** {@code GET /schemas/{full_name}}. */
  record SchemaInfo(String name, String catalogName, String fullName, String schemaId) {}

  /** Body of {@code POST /temporary-table-credentials}. */
  record GenerateTemporaryTableCredential(String tableId, String operation) {}

  /**
   * What that returns: one cloud's credentials, and nothing for the others.
   *
   * <p>The three blocks here are the ones open-source Unity Catalog mints. Databricks' Unity Catalog
   * answers the same endpoint with two more, {@code r2_temp_credentials} and {@code azure_aad}, which
   * this connector does not read — a table backed by either is refused as a cloud it cannot vend for,
   * rather than served with credentials it guessed at.
   *
   * @param expirationTime when the credentials stop working, in epoch milliseconds
   * @param url the storage path the credentials were minted for, normalized by the catalog
   */
  record TemporaryCredentials(
      AwsCredentials awsTempCredentials,
      AzureUserDelegationSas azureUserDelegationSas,
      GcpOauthToken gcpOauthToken,
      Long expirationTime,
      String url) {}

  record AwsCredentials(String accessKeyId, String secretAccessKey, String sessionToken) {}

  record AzureUserDelegationSas(String sasToken) {}

  record GcpOauthToken(String oauthToken) {}

  /** The body Unity Catalog fails with, which carries a message worth passing on. */
  record ErrorResponse(String errorCode, String message) {}
}
