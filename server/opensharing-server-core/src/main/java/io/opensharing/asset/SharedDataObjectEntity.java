package io.opensharing.asset;

import io.opensharing.BaseEntity;
import io.opensharing.ObjectNames;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.DataSourceFormat;
import io.opensharing.share.ShareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * A catalog object included in a share under a recipient-visible alias. A share may include each
 * catalog object once and use each alias once. Rows are deleted with their share.
 */
@Entity
@Table(
    name = "os_shared_data_objects",
    uniqueConstraints = {
      @UniqueConstraint(name = "uk_shared_objects_source", columnNames = {"share_id", "name"}),
      @UniqueConstraint(
          name = "uk_shared_objects_alias",
          columnNames = {"share_id", "shared_as_schema", "shared_as_table"})
    })
public class SharedDataObjectEntity extends BaseEntity {

  /** The owning share. Lazy because callers already hold the share they query by. */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "share_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private ShareEntity share;

  /** The catalog's own stable id for the object, when the catalog reports one. */
  @Column(name = "source_asset_id", length = 255)
  private String sourceAssetId;

  /** Full catalog name as the provider gave it, such as {@code main.sales.orders}. */
  @Column(nullable = false, length = 512)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private AssetType type;

  /** The catalog's finer-grained kind of table, such as {@code MANAGED}; null for schemas. */
  @Column(name = "source_subtype", length = 64)
  private String sourceSubtype;

  /** Data source format for tables, such as Delta; null for schemas. */
  @Enumerated(EnumType.STRING)
  @Column(name = "source_format", length = 32)
  private DataSourceFormat sourceFormat;

  /** Lowercase schema name recipients see. */
  @Column(name = "shared_as_schema", nullable = false, length = 255)
  private String sharedAsSchema;

  /** Empty for schema-level shares; otherwise the lowercase table (or other asset) name. */
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

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
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

  public DataSourceFormat getSourceFormat() {
    return sourceFormat;
  }

  public void setSourceFormat(DataSourceFormat sourceFormat) {
    this.sourceFormat = sourceFormat;
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
    this.sharedAsTable = sharedAsTable == null ? "" : ObjectNames.normalize(sharedAsTable);
  }

  /** Recipient-visible alias: {@code schema} or {@code schema.table}. */
  public String getSharedAs() {
    return sharedAsTable == null || sharedAsTable.isEmpty()
        ? sharedAsSchema
        : sharedAsSchema + "." + sharedAsTable;
  }
}
