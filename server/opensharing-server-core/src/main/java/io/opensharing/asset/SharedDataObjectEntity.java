package io.opensharing.asset;

import io.opensharing.BaseEntity;
import io.opensharing.ObjectNames;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.TableFormat;
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

/** A catalog object included in a share under a recipient-visible alias. */
@Entity
@Table(
    name = "os_shared_data_objects",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_shared_objects_source", columnNames = {"share_id", "name_lower"}),
      @UniqueConstraint(
          name = "uk_shared_objects_alias", columnNames = {"share_id", "shared_as_lower"})
    })
public class SharedDataObjectEntity extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "share_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private ShareEntity share;

  @Column(name = "source_asset_id", length = 255)
  private String sourceAssetId;

  @Column(nullable = false, length = 512)
  private String name;

  @Column(name = "name_lower", nullable = false, length = 512)
  private String nameLower;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private AssetType type;

  @Column(name = "source_subtype", length = 64)
  private String sourceSubtype;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_format", length = 32)
  private TableFormat sourceFormat;

  @Column(name = "shared_as", nullable = false, length = 511)
  private String sharedAs;

  @Column(name = "shared_as_lower", nullable = false, length = 511)
  private String sharedAsLower;

  @Column(name = "added_by", nullable = false, length = 255)
  private String addedBy;

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

  public String getSharedAs() {
    return sharedAs;
  }

  public void setSharedAs(String sharedAs) {
    this.sharedAs = sharedAs;
    this.sharedAsLower = ObjectNames.normalize(sharedAs);
  }

  public String getAddedBy() {
    return addedBy;
  }

  public void setAddedBy(String addedBy) {
    this.addedBy = addedBy;
  }
}
