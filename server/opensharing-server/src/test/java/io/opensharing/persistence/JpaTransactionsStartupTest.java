package io.opensharing.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.runtime.OpenSharing;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Startup migrates an empty database and refuses setups Flyway cannot run on. */
class JpaTransactionsStartupTest {

  @Test
  void migratesAnEmptyDatabaseAndServesTransactions() {
    try (JpaTransactions tx = start("jdbc:h2:mem:startup-fresh", Map.of())) {
      long shares =
          tx.inTransaction(
              true,
              em ->
                  em.createQuery("select count(s) from ShareEntity s", Long.class)
                      .getSingleResult());
      assertEquals(0, shares);
    }
  }

  @Test
  void refusesAConnectionProviderWithoutADataSource() {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () ->
                start(
                    "jdbc:h2:mem:startup-no-datasource",
                    Map.of(
                        "hibernate.connection.provider_class",
                        "org.hibernate.engine.jdbc.connections.internal"
                            + ".DriverManagerConnectionProviderImpl")));
    assertTrue(e.getMessage().contains("does not expose a DataSource"), e::getMessage);
  }

  @Test
  void refusesADatabaseWithoutMigrations() {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () ->
                start(
                    "jdbc:h2:mem:startup-unsupported",
                    Map.of("hibernate.dialect", "org.hibernate.dialect.OracleDialect")));
    assertTrue(e.getMessage().contains("no schema migrations for OracleDialect"), e::getMessage);
  }

  private static JpaTransactions start(String url, Map<String, String> settings) {
    Map<String, String> all = new HashMap<>(settings);
    all.put("hibernate.connection.url", url + ";DB_CLOSE_DELAY=-1");
    return new JpaTransactions(OpenSharing.entities(), all);
  }
}
