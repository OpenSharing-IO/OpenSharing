package io.opensharing.persistence;

import io.opensharing.Transactions;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.function.Function;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.cfg.Configuration;

/**
 * Runs core store work in Hibernate transactions, one session each. Hibernate reads its settings
 * from {@code hibernate.properties} on the classpath.
 */
public final class JpaTransactions implements Transactions, AutoCloseable {

  private final SessionFactory sessions;

  public JpaTransactions(List<Class<?>> entities) {
    Configuration configuration = new Configuration();
    entities.forEach(configuration::addAnnotatedClass);
    this.sessions = configuration.buildSessionFactory();
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
