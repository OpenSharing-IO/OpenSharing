package io.opensharing.recipient;

import io.opensharing.BaseEntity;
import io.opensharing.ObjectNames;
import io.opensharing.auth.Ownership;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Persisted recipient row: someone a provider shares data with. {@code name} is always stored
 * lowercase. Credentials live in {@link RecipientTokenEntity}.
 */
@Entity
@Table(
    name = "os_recipients",
    uniqueConstraints = @UniqueConstraint(name = "uk_recipients_name", columnNames = "name"))
public class RecipientEntity extends BaseEntity {

  static final int MAX_COMMENT_LENGTH = 65536;

  @Column(nullable = false, length = 255)
  private String name;

  @Column(length = MAX_COMMENT_LENGTH)
  private String comment;

  /** userId of whoever created this recipient. Not a foreign key: there is no principal table. */
  @Column(name = "owner_id", nullable = false, length = Ownership.MAX_OWNER_ID_LENGTH)
  private String ownerId;

  /** How the recipient authenticates to the sharing protocol. */
  @Convert(converter = AuthenticationTypeConverter.class)
  @Column(name = "authentication_type", nullable = false, length = 255)
  private AuthenticationType authenticationType;

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

  public AuthenticationType getAuthenticationType() {
    return authenticationType;
  }

  public void setAuthenticationType(AuthenticationType authenticationType) {
    this.authenticationType = authenticationType;
  }
}
