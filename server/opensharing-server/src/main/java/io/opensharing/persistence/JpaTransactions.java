package io.opensharing.persistence;

import io.opensharing.Transactions;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.cfg.Configuration;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.engine.spi.SessionFactoryImplementor;

/**
 * Runs core store work in Hibernate transactions, one session each. Hibernate reads its settings
 * from {@code hibernate.properties} on the classpath.
 *
 * <p>On startup, Flyway applies the migrations in {@code db/migration/<database>} through
 * Hibernate's connection pool, then Hibernate checks the entities against the resulting tables. A
 * schema change is a new migration for every database.
 */
public final class JpaTransactions implements Transactions, AutoCloseable {

  private final SessionFactory sessions;

  public JpaTransactions(List<Class<?>> entities) {
    this(entities, Map.of());
  }

  /** {@code settings} override {@code hibernate.properties}. */
  JpaTransactions(List<Class<?>> entities, Map<String, String> settings) {
    Configuration configuration = new Configuration();
    settings.forEach(configuration::setProperty);
    entities.forEach(configuration::addAnnotatedClass);
    this.sessions = configuration.buildSessionFactory();
    try {
      migrate(sessions.unwrap(SessionFactoryImplementor.class));
      sessions.getSchemaManager().validateMappedObjects();
    } catch (RuntimeException | Error e) {
      sessions.close();
      throw e;
    }
  }

  // Flyway runs on Hibernate's pool so both use the same connection settings, and so an in-memory
  // H2 database, dropped when its last connection closes, keeps the tables Flyway creates.
  private static void migrate(SessionFactoryImplementor sessions) {
    DataSource dataSource =
        sessions
            .getServiceRegistry()
            .requireService(ConnectionProvider.class)
            .unwrap(DataSource.class);
    if (dataSource == null) {
      throw new IllegalStateException(
          "The ConnectionProvider does not expose a DataSource, which Flyway needs; use a provider"
              + " that wraps one, such as HikariCPConnectionProvider");
    }
    // envVars() comes first so the dialect's scripts below take precedence: FLYWAY_LOCATIONS
    // cannot point a database at another dialect's scripts. Other FLYWAY_* variables apply.
    Flyway.configure(JpaTransactions.class.getClassLoader())
        .envVars()
        .dataSource(dataSource)
        .locations("classpath:db/migration/" + database(sessions.getJdbcServices().getDialect()))
        .load()
        .migrate();
  }

  private static String database(Dialect dialect) {
    if (dialect instanceof H2Dialect) {
      return "h2";
    }
    if (dialect instanceof PostgreSQLDialect) {
      return "postgresql";
    }
    if (dialect instanceof MySQLDialect) {
      return "mysql";
    }
    throw new IllegalStateException(
        "no schema migrations for " + dialect.getClass().getSimpleName()
            + "; supported databases are H2, PostgreSQL and MySQL");
  }

  @Override
  public <T> T inTransaction(boolean readOnly, Function<EntityManager, T> work) {
    try (Session session = sessions.openSession()) {
      session.setDefaultReadOnly(readOnly);
      Transaction transaction = session.beginTransaction();
      try {
        T result = work.apply(session);
        transaction.commit();
        return result;
      } catch (RuntimeException | Error e) {
        if (transaction.isActive()) {
          transaction.rollback();
        }
        throw e;
      }
    }
  }

  @Override
  public void close() {
    if (sessions.isOpen()) {
      sessions.close();
    }
  }
}
