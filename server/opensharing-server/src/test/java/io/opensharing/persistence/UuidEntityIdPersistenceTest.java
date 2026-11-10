package io.opensharing.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.opensharing.auth.UserContext;
import io.opensharing.recipient.AuthenticationType;
import io.opensharing.recipient.RecipientEntity;
import io.opensharing.recipient.RecipientStore;
import io.opensharing.runtime.OpenSharing;
import io.opensharing.share.ShareEntity;
import io.opensharing.share.SharePermissionStore;
import io.opensharing.share.SharePrivilege;
import io.opensharing.share.ShareStore;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Entity ids persist as {@link UUID} and map to the database's native UUID type, not
 * {@code varchar(36)}: PostgreSQL and H2 store {@code uuid}, MySQL {@code binary(16)}.
 */
class UuidEntityIdPersistenceTest {

  private static final UserContext ALICE = UserContext.fromUserIdAndName("alice", "Alice");

  private static JpaTransactions tx;
  private static ShareStore shares;
  private static RecipientStore recipients;
  private static SharePermissionStore permissions;

  @BeforeAll
  static void openStore() {
    tx = new JpaTransactions(OpenSharing.entities());
    shares = new ShareStore(tx);
    recipients = new RecipientStore(tx);
    permissions = new SharePermissionStore(tx);
  }

  @AfterAll
  static void closeStore() {
    tx.close();
  }

  @Test
  void persistsAndReloadsEntitiesWithUuidIds() {
    ShareEntity share = shares.create(ALICE, "uuid-ids", null, null, null);
    RecipientEntity recipient =
        recipients.create(
            ALICE,
            "uuid-ids",
            null,
            AuthenticationType.TOKEN,
            "activation-code-uuid-ids",
            Instant.now().plusSeconds(60));

    // The generated id is a UUID, and the row reloads by it.
    assertInstanceOf(UUID.class, share.getId());
    assertInstanceOf(UUID.class, recipient.getId());
    tx.inTransaction(
        true,
        em -> {
          assertEquals(share.getName(), em.find(ShareEntity.class, share.getId()).getName());
          assertEquals(
              recipient.getName(), em.find(RecipientEntity.class, recipient.getId()).getName());
          return null;
        });

    // Queries that reference other entities by their id column find the rows.
    tx.inTransaction(
        false,
        em -> {
          permissions.grant(em, share, recipient, SharePrivilege.SELECT);
          return null;
        });
    ShareEntity granted = permissions.listSharesFor(recipient.getId(), 0, 1).get(0);
    assertEquals(share.getId(), granted.getId());
  }

  @Test
  void mapsIdsToTheDialectsNativeUuidType() {
    // The in-memory H2 database is the dialect these tests run against; PostgreSQL and MySQL
    // follow from the same Hibernate UUID mapping.
    String type =
        tx.inTransaction(
            true,
            UuidEntityIdPersistenceTest::columnTypeOf);
    assertEquals("UUID", type);
  }

  private static String columnTypeOf(EntityManager em) {
    Object type =
        em.createNativeQuery(
                "select data_type from information_schema.columns"
                    + " where table_name = 'OS_SHARES' and column_name = 'ID'")
            .getSingleResult();
    assertNotNull(type);
    return type.toString();
  }
}
