package io.opensharing.persistence;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * V1 leaves Hibernate's update nothing structural to do: the migrated schema is what the entities
 * map to, so a V1 that misses a table, column or type shows up here as a statement to apply.
 */
class JpaTransactionsTest {

  @Test
  void migratesASchemaWithNoTablesOrColumnsLeftToAdd() {
    String url = "jdbc:h2:mem:matches-v1;DB_CLOSE_DELAY=-1";
    Flyway.configure(getClass().getClassLoader())
        .dataSource(url, "sa", "")
        .locations("classpath:db/migration/h2")
        .load()
        .migrate();

    List<String> statements = HibernateUpdateStatements.of(url, "sa", "");
    // H2 reports the index backing a unique constraint under a generated name, so Hibernate
    // cannot match the constraint and re-creates it. Nothing besides that may change.
    assertTrue(
        statements.stream().noneMatch(JpaTransactionsTest::addsTablesOrColumns),
        () -> String.join(";\n", statements));
  }

  private static boolean addsTablesOrColumns(String statement) {
    String sql = statement.toLowerCase();
    return sql.contains("create table") || sql.contains("add column");
  }
}
