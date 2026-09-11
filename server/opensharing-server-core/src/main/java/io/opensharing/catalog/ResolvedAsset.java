package io.opensharing.catalog;

import java.util.List;
import java.util.Set;

/**
 * What the catalog knows about an asset: where it physically lives and how it can be read.
 *
 * <p>A shared object keeps a snapshot of this, so a listing can be answered from the database instead
 * of a catalog call per row, and every resolution rewrites the snapshot where the catalog's answer has
 * moved on — a relocated asset, a format or subtype that changed, an id handed back different. The
 * two answers that end the snapshot rather than update it are the asset being gone and the owner no
 * longer being allowed to read it; both withdraw the object instead, so it stops being listed rather
 * than failing every read from then on.
 *
 * <p>Three fields are left out of that snapshot on purpose, for two different reasons. {@link
 * #metadataLocation} is used from the resolution in hand, because an Iceberg table's metadata pointer
 * moves with every commit and a stored one would be a pointer to the table as it was. {@link #schema}
 * and {@link #partitionColumns} are carried but not served at all: both are stated by the format
 * itself, and what a recipient is told comes from there — the Delta log, or an Iceberg metadata
 * document — since that is the copy the bytes were written under. Storing either would be keeping a
 * second answer to a question that already has one.
 *
 * @param type which kind of asset this is, of the kinds this server serves: a table or a schema
 * @param identifier the catalog's canonical name for it, which is what every later resolution asks
 *     about — the name a recipient sees is the alias it was shared under, not this
 * @param catalogAssetId the catalog's own durable id, where it has one, which is what catalogs that
 *     vend credentials by id rather than by path mint against. Recorded and refreshed with the rest of
 *     the snapshot, and visible to an administrator: a share names an asset by name, so an asset
 *     dropped and recreated under that name is served as the same shared object, and this changing
 *     underneath is the only trace of it.
 * @param storageLocation where the bytes are: what a credential gets scoped to, what a signed url is
 *     built from, and what a path handed back by a log is checked against
 * @param metadataLocation the pointer a client needs to interpret those bytes, such as an Iceberg
 *     table's current metadata JSON. Null for formats that need no such pointer.
 * @param format the format the bytes are in — Delta, Iceberg or Parquet — which decides how the table
 *     is served, and by which endpoint
 * @param schema the source's schema as the catalog states it, or null if the catalog does not report
 *     one. Carried rather than served: what a recipient is told is the schema the format itself
 *     states — the Delta log for a Delta or Parquet table, the metadata document for an Iceberg one —
 *     because that is the one the bytes they are about to read were written under, and a catalog's
 *     copy can lag it. Kept because a connector for a catalog whose assets state no schema of their
 *     own would have nowhere else to put it.
 * @param partitionColumns the columns the source is partitioned by, in order, or empty when it is
 *     unpartitioned or the catalog does not say. Unity Catalog reports these through the partition
 *     index on each column; an Iceberg catalog through the partition spec. Carried on the same terms
 *     as {@link #schema}, and for the same reason.
 * @param subtype the catalog's own refinement of the type — {@code MANAGED} or {@code EXTERNAL} for a
 *     table it stores, and {@code VIEW}, {@code MATERIALIZED_VIEW} or {@code STREAMING_TABLE} for the
 *     things that are tables to a catalog but not always to a reader
 * @param accessModes the modes the catalog can support, which in practice means {@code DIR}: a
 *     recipient reading a directory needs credentials scoped to the location, and whether any exist
 *     is the catalog's answer to give. {@code URL} is added by the server instead, since serving one
 *     depends on what this build can read rather than on anything the catalog holds.
 * @param auxiliaryLocations other locations the catalog approves for this asset, which a credential
 *     may also be scoped to and a file may be served from. A table whose data has spilled beyond its
 *     own prefix is the case: without these, a path under one of them would be read as a path outside
 *     the table.
 */
public record ResolvedAsset(
    AssetType type,
    String identifier,
    String catalogAssetId,
    String storageLocation,
    String metadataLocation,
    TableFormat format,
    String schema,
    List<String> partitionColumns,
    String subtype,
    Set<AccessMode> accessModes,
    List<String> auxiliaryLocations) {

  public ResolvedAsset {
    accessModes = accessModes == null ? Set.of() : Set.copyOf(accessModes);
    auxiliaryLocations = auxiliaryLocations == null ? List.of() : List.copyOf(auxiliaryLocations);
    partitionColumns = partitionColumns == null ? List.of() : List.copyOf(partitionColumns);
  }

  public static Builder builder(AssetType type, String identifier) {
    return new Builder(type, identifier);
  }

  public static final class Builder {
    private final AssetType type;
    private final String identifier;
    private String catalogAssetId;
    private String storageLocation;
    private String metadataLocation;
    private TableFormat format;
    private String schema;
    private List<String> partitionColumns = List.of();
    private String subtype;
    private Set<AccessMode> accessModes = Set.of();
    private List<String> auxiliaryLocations = List.of();

    private Builder(AssetType type, String identifier) {
      this.type = type;
      this.identifier = identifier;
    }

    public Builder catalogAssetId(String value) {
      this.catalogAssetId = value;
      return this;
    }

    public Builder storageLocation(String value) {
      this.storageLocation = value;
      return this;
    }

    public Builder metadataLocation(String value) {
      this.metadataLocation = value;
      return this;
    }

    public Builder format(TableFormat value) {
      this.format = value;
      return this;
    }

    public Builder schema(String value) {
      this.schema = value;
      return this;
    }

    public Builder partitionColumns(List<String> value) {
      this.partitionColumns = value;
      return this;
    }

    public Builder subtype(String value) {
      this.subtype = value;
      return this;
    }

    public Builder accessModes(Set<AccessMode> value) {
      this.accessModes = value;
      return this;
    }

    public Builder auxiliaryLocations(List<String> value) {
      this.auxiliaryLocations = value;
      return this;
    }

    public ResolvedAsset build() {
      return new ResolvedAsset(
          type,
          identifier,
          catalogAssetId,
          storageLocation,
          metadataLocation,
          format,
          schema,
          partitionColumns,
          subtype,
          accessModes,
          auxiliaryLocations);
    }
  }
}
