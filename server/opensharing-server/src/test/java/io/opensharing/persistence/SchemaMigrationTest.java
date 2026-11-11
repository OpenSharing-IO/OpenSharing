package io.opensharing.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.recipient.AuthenticationType;
import io.opensharing.recipient.RecipientEntity;
import io.opensharing.recipient.RecipientTokenEntity;
import io.opensharing.runtime.OpenSharing;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Every other test runs on H2; these run the PostgreSQL and MySQL migrations. */
@Testcontainers(disabledWithoutDocker = true)
class SchemaMigrationTest {

  @Container
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Container private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

  @Test
  void migratesPostgres() {
    assertMigratesAndRoundTrips(POSTGRES);
  }

  @Test
  void migratesMysql() {
    assertMigratesAndRoundTrips(MYSQL);
  }

  private static void assertMigratesAndRoundTrips(JdbcDatabaseContainer<?> database) {
    Map<String, String> settings =
        Map.of(
            "hibernate.connection.url", database.getJdbcUrl(),
            "hibernate.connection.username", database.getUsername(),
            "hibernate.connection.password", database.getPassword());
    String comment = "c".repeat(65536);
    Instant expiresAt = Instant.parse("2030-01-02T03:04:05.123456Z");

    java.util.UUID tokenId;
    try (JpaTransactions transactions = new JpaTransactions(OpenSharing.entities(), settings)) {
      tokenId =
          transactions.inTransaction(
              false,
              em -> {
                RecipientEntity recipient = new RecipientEntity();
                recipient.setName("alice");
                recipient.setComment(comment);
                recipient.setOwnerId("owner");
                recipient.setAuthenticationType(AuthenticationType.TOKEN);
                em.persist(recipient);
                RecipientTokenEntity token = new RecipientTokenEntity();
                token.setRecipient(recipient);
                token.setActivationCode("code");
                token.setActivated(true);
                token.setExpiresAt(expiresAt);
                em.persist(token);
                return token.getId();
              });
    }

    // A restart finds nothing left to migrate.
    try (JpaTransactions transactions = new JpaTransactions(OpenSharing.entities(), settings)) {
      RecipientTokenEntity token =
          transactions.inTransaction(true, em -> em.find(RecipientTokenEntity.class, tokenId));
      assertTrue(token.isActivated());
      assertEquals(expiresAt, token.getExpiresAt());
      assertEquals(comment, token.getRecipient().getComment());
      assertEquals(AuthenticationType.TOKEN, token.getRecipient().getAuthenticationType());
    }

    // V1 is exactly what the entities map to: Hibernate's update has nothing to change.
    assertTrue(
        HibernateUpdateStatements.of(
                database.getJdbcUrl(), database.getUsername(), database.getPassword())
            .isEmpty(),
        database.getDockerImageName());
  }
}
