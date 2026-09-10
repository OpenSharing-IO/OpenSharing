package io.opensharing.share;

import io.opensharing.BaseEntity;
import io.opensharing.ObjectNames;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A named, access-controlled collection of assets. */
@Entity
@Table(
    name = "os_shares",
    uniqueConstraints =
        @UniqueConstraint(name = "uk_shares_name_lower", columnNames = "name_lower"))
public class ShareEntity extends BaseEntity {

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Column(name = "name_lower", nullable = false, length = 255)
  private String nameLower;

  @Column(name = "display_name", length = 255)
  private String displayName;

  @Column(name = "comment", length = 8192)
  private String comment;

  /**
   * A catalog's own identity for whoever created this share — for the {@code unity} connector, a
   * Unity Catalog user id; for {@code local}, whatever the configured principal resolves to. Never
   * a foreign key: there is no principal table to reference, on purpose — see {@code
   * AdminAuthenticationFilter}.
   */
  @Column(name = "owner_id", nullable = false, length = 255)
  private String ownerId;

  @Column(name = "created_by", nullable = false, length = 255)
  private String createdBy;

  @Column(name = "updated_by", nullable = false, length = 255)
  private String updatedBy;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(
      name = "os_share_properties",
      joinColumns = @JoinColumn(name = "share_id"),
      uniqueConstraints =
          @UniqueConstraint(
              name = "uk_share_properties",
              columnNames = {"share_id", "property_key"}))
  @MapKeyColumn(name = "property_key", length = 255)
  @Column(name = "property_value", length = 1000)
  private Map<String, String> properties = new LinkedHashMap<>();

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
    this.nameLower = ObjectNames.normalize(name);
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getComment() {
    return comment;
  }

  public void setComment(String comment) {
    this.comment = comment;
  }

  /**
   * Read-only, so a caller reading a share's properties cannot quietly rewrite the row behind it:
   * what Hibernate hands back is the persistent collection itself, and a change to it is a change to
   * the entity whether or not anyone meant one.
   */
  public Map<String, String> getProperties() {
    return Collections.unmodifiableMap(properties);
  }

  public void setProperties(Map<String, String> properties) {
    this.properties = properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
  }

  public String getOwnerId() {
    return ownerId;
  }

  public void setOwnerId(String ownerId) {
    this.ownerId = ownerId;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(String createdBy) {
    this.createdBy = createdBy;
  }

  public String getUpdatedBy() {
    return updatedBy;
  }

  public void setUpdatedBy(String updatedBy) {
    this.updatedBy = updatedBy;
  }
}
