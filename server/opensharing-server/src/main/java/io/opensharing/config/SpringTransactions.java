package io.opensharing.config;

import io.opensharing.Transactions;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.function.Function;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs core store work in Spring-managed JPA transactions. */
final class SpringTransactions implements Transactions {

  private final TransactionTemplate readWrite;
  private final TransactionTemplate readOnly;
  private final EntityManager entityManager;

  SpringTransactions(PlatformTransactionManager transactionManager, EntityManagerFactory factory) {
    this.readWrite = new TransactionTemplate(transactionManager);
    this.readOnly = new TransactionTemplate(transactionManager);
    this.readOnly.setReadOnly(true);
    this.entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory);
  }

  @Override
  public <T> T inTransaction(boolean readOnly, Function<EntityManager, T> work) {
    TransactionTemplate template = readOnly ? this.readOnly : readWrite;
    return template.execute(status -> work.apply(entityManager));
  }
}
