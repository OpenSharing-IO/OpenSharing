package io.opensharing.asset;

import io.opensharing.BaseEntity;
import io.opensharing.ObjectNames;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.DataSourceFormat;
import io.opensharing.share.ShareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.List;

/**
 * A catalog object included in a share under a recipient-visible alias. A share may include each
 * catalog object once and use each alias once. {@code ShareStore} deletes them with their share.
 */
@Entity
@Table(
    name = "os_shared_data_objects",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_shared_objects_source",
          columnNames = {"share_id", "full_name"}),
      @UniqueConstraint(
          name = "uk_shared_objects_alias",
          columnNames = {"share_id", "shared_as_schema", "shared_as_table"})
    })
public class SharedDataObjectEntity extends BaseEntity {

  /** The owning share. Lazy because callers already hold the share they query by. */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "share_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_shared_objects_share"))
  private ShareEntity share;

  /** The catalog's own stable id for the object, when the catalog reports one. */
  @Column(name = "source_asset_id", length = 255)
  private String sourceAssetId;

  /** Full catalog name as the provider gave it, such as {@code main.sales.orders}. */
  @Column(name = "full_name", nullable = false, length = 512)
  private String fullName;

  @Convert(converter = AssetTypeConverter.class)
  @Column(nullable = false, length = 255)
  private AssetType type;

  /** Data source format for tables, such as Delta. */
  @Convert(converter = DataSourceFormatConverter.class)
  @Column(name = "source_format", length = 255)
  private DataSourceFormat sourceFormat;

  /** Storage location of a table as the catalog reported it when shared. */
  @Column(name = "storage_location", length = 2048)
  private String storageLocation;

  /** Other storage locations of a table as the catalog reported them when shared, one per line. */
  @Column(name = "auxiliary_locations", length = 8192)
  private String auxiliaryLocations;

  /**
   * The first version of a table that recipients may read, or null for all of them. A read that
   * resolves to an earlier version is refused.
   */
  @Column(name = "start_version")
  private Long startVersion;

  /** Lowercase schema name recipients see. */
  @Column(name = "shared_as_schema", nullable = false, length = 255)
  private String sharedAsSchema;

  /** Lowercase table (or other asset) name recipients see. */
  @Column(name = "shared_as_table", nullable = false, length = 255)
  private String sharedAsTable;

  public ShareEntity getShare() {
    return share;
  }

  public void setShare(ShareEntity share) {
    this.share = share;
  }

  public String getSourceAssetId() {
    return sourceAssetId;
  }

  public void setSourceAssetId(String sourceAssetId) {
    this.sourceAssetId = sourceAssetId;
  }

  public String getFullName() {
    return fullName;
  }

  public void setFullName(String fullName) {
    this.fullName = fullName;
  }

  public AssetType getType() {
    return type;
  }

  public void setType(AssetType type) {
    this.type = type;
  }

  public DataSourceFormat getSourceFormat() {
    return sourceFormat;
  }

  public void setSourceFormat(DataSourceFormat sourceFormat) {
    this.sourceFormat = sourceFormat;
  }

  public String getStorageLocation() {
    return storageLocation;
  }

  public void setStorageLocation(String storageLocation) {
    this.storageLocation = storageLocation;
  }

  public List<String> getAuxiliaryLocations() {
    return auxiliaryLocations == null ? List.of() : List.of(auxiliaryLocations.split("\n"));
  }

  public void setAuxiliaryLocations(List<String> auxiliaryLocations) {
    this.auxiliaryLocations =
        auxiliaryLocations.isEmpty() ? null : String.join("\n", auxiliaryLocations);
  }

  public Long getStartVersion() {
    return startVersion;
  }

  public void setStartVersion(Long startVersion) {
    this.startVersion = startVersion;
  }

  public String getSharedAsSchema() {
    return sharedAsSchema;
  }

  public void setSharedAsSchema(String sharedAsSchema) {
    this.sharedAsSchema = ObjectNames.normalize(sharedAsSchema);
  }

  public String getSharedAsTable() {
    return sharedAsTable;
  }

  public void setSharedAsTable(String sharedAsTable) {
    this.sharedAsTable = ObjectNames.normalize(sharedAsTable);
  }

  /** Recipient-visible alias: {@code schema.table}. */
  public String getSharedAs() {
    return sharedAsSchema + "." + sharedAsTable;
  }
}
