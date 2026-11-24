package io.opensharing.asset.table.iceberg;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * What a recipient needs to read an Iceberg table: its current metadata, and read credentials for
 * its location when the recipient asked for them.
 *
 * @param metadataLocation where the table's current metadata JSON is
 * @param metadata that metadata JSON, as stored
 * @param credentials read credentials for the table's files, the most specific one covering its
 *     location first; empty when not asked for
 */
public record IcebergTable(
    String metadataLocation, ObjectNode metadata, List<IcebergStorageCredential> credentials) {
  @Override
  public String toString() {
    return "IcebergTable[metadataLocation="
        + metadataLocation
        + ", credentials="
        + credentials
        + "]";
  }
}
