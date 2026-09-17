package io.opensharing.recipient;

import io.opensharing.BaseEntity;
import io.opensharing.ObjectNames;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Persisted recipient row. {@code name} is always stored lowercase. */
@Entity
@Table(
    name = "os_recipients",
    uniqueConstraints = @UniqueConstraint(name = "uk_recipients_name", columnNames = "name"))
public class RecipientEntity extends BaseEntity {

  @Column(nullable = false, length = 255)
  private String name;

  @Column(length = 8192)
  private String comment;

  @Column(name = "owner_id", nullable = false, length = 255)
  private String ownerId;

  @Column(name = "created_by", nullable = false, length = 255)
  private String createdBy;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = ObjectNames.normalize(name);
  }

  public String getComment() {
    return comment;
  }

  public void setComment(String comment) {
    this.comment = comment;
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
}
