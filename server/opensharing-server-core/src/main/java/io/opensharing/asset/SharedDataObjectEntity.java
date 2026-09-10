package io.opensharing.asset;

import io.opensharing.BaseEntity;
import io.opensharing.ObjectNames;
import io.opensharing.catalog.AccessMode;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.TableFormat;
import io.opensharing.share.ShareEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One object granted into a share: the canonical name it has in the catalog, the alias recipients see
 * instead, and a snapshot of what the catalog last reported about it.
 *
 * <p>The alias is a two-level {@code schema.name}, which is what makes the protocol's schema level
 * exist: schemas are not stored in their own right, they are the distinct first halves of the aliases
 * in a share. Both halves are kept normalized so lookups can be case-insensitive.
 *
 * <p>A {@link AssetType#SCHEMA} row is the exception, and the reason the name half is nullable: it is
 * shared under a one-level alias and stands for every table the catalog puts in that schema, none of
 * which gets a row of its own. Such a table is served as {@link #inSharedSchema an object assembled
 * per request} instead.
 *
 * <p>{@code added_at} is {@link BaseEntity#getCreatedAt()} — a row is created by being added.
 */
@Entity
@Table(
    name = "os_shared_data_objects",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_shared_objects_shared_as",
          columnNames = {"share_id", "shared_as_schema_lower", "shared_as_name_lower"}),
      @UniqueConstraint(
          name = "uk_shared_objects_source",
          columnNames = {"share_id", "name_lower"})
    },
    indexes = {
      @Index(
          name = "ix_shared_objects_listing",
          columnList = "share_id, type, status, shared_as_schema_lower, shared_as_name_lower")
    })
public class SharedDataObjectEntity extends BaseEntity {

  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "share_id", nullable = false)
  private ShareEntity share;

  /**
   * How long a canonical catalog name may be. The cap exists so that {@code uk_shared_objects_source},
   * which spans this column and the share id, stays inside InnoDB's 3072-byte index limit under
   * utf8mb4 — that is what lets one schema serve H2, Postgres and MySQL alike.
   */
  public static final int MAX_SOURCE_NAME_LENGTH = 512;

  /** Canonical catalog name, e.g. a three-level {@code main.sales.orders}. */
  @Column(name = "name", nullable = false, length = MAX_SOURCE_NAME_LENGTH)
  private String name;

  @Column(name = "name_lower", nullable = false, length = MAX_SOURCE_NAME_LENGTH)
  private String nameLower;

  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false, length = 32)
  private AssetType type;

  /** The catalog's refinement of the type, e.g. {@code VIEW}. */
  @Column(name = "source_subtype", length = 64)
  private String sourceSubtype;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_format", length = 32)
  private TableFormat sourceFormat;

  /** Catalog-internal id, required by catalogs that vend credentials by id rather than by path. */
  @Column(name = "source_asset_id", length = 255)
  private String sourceAssetId;

  @Column(name = "shared_as", nullable = false, length = 511)
  private String sharedAs;

  @Column(name = "shared_as_schema", nullable = false, length = 255)
  private String sharedAsSchema;

  @Column(name = "shared_as_schema_lower", nullable = false, length = 255)
  private String sharedAsSchemaLower;

  /**
   * Null when the object shared is a schema, whose alias is the schema level alone. The uniqueness of
   * a schema alias is therefore checked in {@link SharedDataObjectService} rather than by {@code
   * uk_shared_objects_shared_as}, since a unique index does not constrain rows with a null column.
   */
  @Column(name = "shared_as_name", length = 255)
  private String sharedAsName;

  @Column(name = "shared_as_name_lower", length = 255)
  private String sharedAsNameLower;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private SharedObjectStatus status = SharedObjectStatus.ACTIVE;

  /** A catalog's own identity for whoever added this object — see {@code ShareEntity}. */
  @Column(name = "added_by", nullable = false, length = 255)
  private String addedBy;

  @Column(name = "updated_by", nullable = false, length = 255)
  private String updatedBy;

  @Column(name = "storage_location", length = 2048)
  private String storageLocation;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(
      name = "os_shared_object_access_modes",
      joinColumns = @JoinColumn(name = "shared_object_id"))
  @Enumerated(EnumType.STRING)
  @Column(name = "access_mode", length = 16)
  private Set<AccessMode> accessModes = new LinkedHashSet<>();

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(
      name = "os_shared_object_auxiliary_locations",
      joinColumns = @JoinColumn(name = "shared_object_id"))
  @Column(name = "location", length = 2048)
  private List<String> auxiliaryLocations = new ArrayList<>();

  /**
   * True for a table a shared schema covers rather than one shared in its own right. Such a table has
   * no row and must never get one, so this is what every write path checks.
   */
  @Transient private boolean inSharedSchema;

  /**
   * A table that a shared schema contains, in the same form as one shared in its own right, so that
   * everything downstream — credential vending, the Delta operations, the listing mapper — serves it
   * without knowing the difference.
   *
   * <p>Deliberately never stored. A schema grant is a grant, not a copy: the catalog is what knows
   * which tables the schema holds, and it may answer differently between two requests, so writing
   * rows here would create a second, staler answer to a question the catalog already answers. The
   * table borrows the grant's share and authorship, and takes the catalog's own asset id as the id a
   * recipient sees, which is stable across requests in a way a freshly generated one would not be.
   */
  public static SharedDataObjectEntity inSharedSchema(
      SharedDataObjectEntity schemaGrant, ResolvedAsset resolved, String tableName) {
    SharedDataObjectEntity table = new SharedDataObjectEntity();
    table.inSharedSchema = true;
    table.setId(
        resolved.catalogAssetId() == null ? resolved.identifier() : resolved.catalogAssetId());
    table.setShare(schemaGrant.getShare());
    table.setName(resolved.identifier());
    table.setType(AssetType.TABLE);
    table.setSharedAs(schemaGrant.getSharedAsSchema() + "." + tableName);
    table.setAddedBy(schemaGrant.getAddedBy());
    table.setUpdatedBy(schemaGrant.getUpdatedBy());
    return table;
  }

  public boolean isInSharedSchema() {
    return inSharedSchema;
  }

  public ShareEntity getShare() {
    return share;
  }

  public void setShare(ShareEntity share) {
    this.share = share;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
    this.nameLower = ObjectNames.normalize(name);
  }

  public AssetType getType() {
    return type;
  }

  public void setType(AssetType type) {
    this.type = type;
  }

  public String getSourceSubtype() {
    return sourceSubtype;
  }

  public void setSourceSubtype(String sourceSubtype) {
    this.sourceSubtype = sourceSubtype;
  }

  public TableFormat getSourceFormat() {
    return sourceFormat;
  }

  public void setSourceFormat(TableFormat sourceFormat) {
    this.sourceFormat = sourceFormat;
  }

  public String getSourceAssetId() {
    return sourceAssetId;
  }

  public void setSourceAssetId(String sourceAssetId) {
    this.sourceAssetId = sourceAssetId;
  }

  public String getSharedAs() {
    return sharedAs;
  }

  /**
   * @param sharedAs a two-level {@code schema.name}
   * @throws IllegalArgumentException if the alias is not exactly two levels
   */
  public void setSharedAs(String sharedAs) {
    String[] parts = SharedAliases.split(sharedAs);
    this.sharedAs = sharedAs;
    this.sharedAsSchema = parts[0];
    this.sharedAsSchemaLower = ObjectNames.normalize(parts[0]);
    this.sharedAsName = parts[1];
    this.sharedAsNameLower = ObjectNames.normalize(parts[1]);
  }

  /**
   * Shares a schema under a one-level alias. The alias occupies the schema level and there is no name
   * level, because what a recipient sees is the schema itself and, inside it, whatever the catalog
   * says the schema contains.
   */
  public void setSharedAsSchema(String alias) {
    String validated = SharedAliases.schema(alias);
    this.sharedAs = validated;
    this.sharedAsSchema = validated;
    this.sharedAsSchemaLower = ObjectNames.normalize(validated);
    this.sharedAsName = null;
    this.sharedAsNameLower = null;
  }

  public String getSharedAsSchema() {
    return sharedAsSchema;
  }

  public String getSharedAsSchemaLower() {
    return sharedAsSchemaLower;
  }

  public String getSharedAsName() {
    return sharedAsName;
  }

  public String getSharedAsNameLower() {
    return sharedAsNameLower;
  }

  public SharedObjectStatus getStatus() {
    return status;
  }

  public void setStatus(SharedObjectStatus status) {
    this.status = status;
  }

  public String getAddedBy() {
    return addedBy;
  }

  public void setAddedBy(String addedBy) {
    this.addedBy = addedBy;
  }

  public String getUpdatedBy() {
    return updatedBy;
  }

  public void setUpdatedBy(String updatedBy) {
    this.updatedBy = updatedBy;
  }

  public String getStorageLocation() {
    return storageLocation;
  }

  public void setStorageLocation(String storageLocation) {
    this.storageLocation = storageLocation;
  }

  /**
   * Read-only, so a caller reading how an object may be read cannot quietly change it: what
   * Hibernate hands back is the persistent collection itself, and a change to it is a change to the
   * row whether or not anyone meant one.
   */
  public Set<AccessMode> getAccessModes() {
    return Collections.unmodifiableSet(accessModes);
  }

  public void setAccessModes(Set<AccessMode> accessModes) {
    this.accessModes =
        accessModes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(accessModes);
  }

  /** Read-only, for the same reason as {@link #getAccessModes()}. */
  public List<String> getAuxiliaryLocations() {
    return Collections.unmodifiableList(auxiliaryLocations);
  }

  public void setAuxiliaryLocations(List<String> auxiliaryLocations) {
    this.auxiliaryLocations =
        auxiliaryLocations == null ? new ArrayList<>() : new ArrayList<>(auxiliaryLocations);
  }
}
