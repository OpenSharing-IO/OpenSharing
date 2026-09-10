package io.opensharing.asset.iceberg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opensharing.asset.AssetResolutionService;
import io.opensharing.asset.CredentialVendingService;
import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.asset.storage.StoragePaths;
import io.opensharing.asset.storage.StorageReader;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.catalog.TableFormat;
import io.opensharing.http.ApiException;
import io.opensharing.http.ErrorCodes;
import io.opensharing.protocol.IcebergLoadTable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Answers the Iceberg REST catalog's {@code loadTable} for a shared table.
 *
 * <p>An Iceberg table needs no replay to be read: its metadata JSON already says what its snapshots,
 * schemas and manifests are, and an engine that can read Iceberg does the rest. So this relays that
 * document and hands over credentials for the table's location, which together are the whole of what
 * a recipient needs — the same bargain as {@code dir} access mode, said in the shape Iceberg clients
 * already speak.
 *
 * <p>Credentials go out with every load, unlike a catalog inside one organisation where a client may
 * hold its own and ask for delegation only when it does not. A recipient of a share never holds any:
 * vended credentials are the only way it can read a byte, so there is nothing to withhold until it
 * asks.
 */
@Service
public class IcebergTableLoader {

  private final AssetResolutionService resolution;
  private final CredentialVendingService credentials;
  private final StorageReader storage;
  private final ObjectMapper json;

  public IcebergTableLoader(
      AssetResolutionService resolution,
      CredentialVendingService credentials,
      StorageReader storage,
      ObjectMapper json) {
    this.resolution = resolution;
    this.credentials = credentials;
    this.storage = storage;
    this.json = json;
  }

  public IcebergLoadTable load(SharedDataObjectEntity table) {
    ResolvedAsset resolved = resolution.resolveForServing(table);
    requireIceberg(table, resolved);
    String metadataLocation = metadataLocationOf(table, resolved);
    StorageCredentials minted = credentials.mint(table, resolved, resolved.storageLocation());
    // A table the catalog vends nothing for is on storage that needs nothing, so there is no grant
    // to relay — the engine reads it the same way this server just did.
    Map<String, String> properties =
        minted == null ? Map.of() : VendedCredentials.propertiesOf(minted);
    return new IcebergLoadTable(
        metadataLocation,
        metadata(metadataLocation, minted),
        properties,
        minted == null
            ? List.of()
            : List.of(new IcebergLoadTable.Credential(minted.prefix(), properties)));
  }

  /**
   * The table's metadata document, relayed as it stands. It is read with the credentials just minted
   * for the recipient, so the server looks inside the table with exactly the access it is about to
   * hand over, and only ever at the one file the catalog pointed it at.
   */
  private JsonNode metadata(String metadataLocation, StorageCredentials minted) {
    byte[] bytes = storage.read(metadataLocation, minted);
    try {
      JsonNode metadata = json.readTree(new String(bytes, StandardCharsets.UTF_8));
      if (!metadata.isObject()) {
        throw notMetadata(metadataLocation, "it is not a JSON object");
      }
      return metadata;
    } catch (IOException e) {
      throw notMetadata(metadataLocation, e.getMessage());
    }
  }

  /**
   * A table of another format is reported as missing rather than refused, because that is what it is
   * to an Iceberg client: this catalog has no such table, and a client asking whether it exists gets
   * an answer it can act on rather than an error it must interpret.
   */
  private static void requireIceberg(SharedDataObjectEntity table, ResolvedAsset resolved) {
    if (resolved.format() == TableFormat.ICEBERG) {
      return;
    }
    throw ApiException.notFound(
        "table '"
            + table.getSharedAsSchema()
            + "."
            + table.getSharedAsName()
            + "' is not an Iceberg table"
            + (resolved.format() == null ? "" : ", but a " + resolved.format().wireName() + " one")
            + "; the Iceberg catalog holds only the Iceberg tables of a share");
  }

  /**
   * Where the catalog says the table's current metadata is, which must be inside the table's own
   * location: the credentials are scoped to that location, and a pointer out of it is either a
   * catalog this server should not be following or one whose bytes it could not read anyway.
   */
  private static String metadataLocationOf(
      SharedDataObjectEntity table, ResolvedAsset resolved) {
    String location = resolved.metadataLocation();
    if (location == null || location.isBlank()) {
      throw new ApiException(
          HttpStatus.BAD_GATEWAY,
          ErrorCodes.CATALOG_ERROR,
          "the catalog does not say where the metadata of '"
              + resolved.identifier()
              + "' is, so the table cannot be loaded; a recipient can still read it with "
              + "temporary-table-credentials against its storage location");
    }
    String root = resolved.storageLocation();
    if (root == null || root.isBlank()) {
      throw ApiException.notFound(
          "the catalog reports no storage location for '" + resolved.identifier() + "'");
    }
    if (!StoragePaths.isInside(location, root)) {
      throw ApiException.permissionDenied(
          "the metadata of '"
              + table.getSharedAsSchema()
              + "."
              + table.getSharedAsName()
              + "' is outside the location shared with the recipient");
    }
    return location;
  }

  private static ApiException notMetadata(String metadataLocation, String because) {
    return new ApiException(
        HttpStatus.BAD_GATEWAY,
        ErrorCodes.CATALOG_ERROR,
        "'" + metadataLocation + "' is not an Iceberg metadata document: " + because);
  }
}
