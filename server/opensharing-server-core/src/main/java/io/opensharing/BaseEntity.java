package io.opensharing;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Instant;
import java.util.UUID;

/** Server-assigned identity and audit columns shared by every stored entity. */
@MappedSuperclass
public abstract class BaseEntity {

  @Id
  @Column(name = "id", length = 36, nullable = false, updatable = false)
  private String id = UUID.randomUUID().toString();

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  void onPersist() {
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }

  @PreUpdate
  void onUpdate() {
    this.updatedAt = Instant.now();
  }

  public String getId() {
    return id;
  }

  /**
   * Adopts an id chosen elsewhere, for the entities whose identity is not the server's to invent. Only
   * meaningful before the row is written: the column is not updatable.
   */
  protected void setId(String id) {
    this.id = id;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
