package io.opensharing;

import jakarta.persistence.EntityManager;
import java.util.function.Function;

/**
 * Runs store work in one database transaction. Each host supplies its own, backed by its own
 * persistence setup; OpenSharing's entities must be registered with it.
 */
public interface Transactions {

  /**
   * Runs {@code work} in a new transaction and commits it, or rolls it back when {@code work}
   * throws. Entities returned from {@code work} are detached once it returns.
   */
  <T> T inTransaction(boolean readOnly, Function<EntityManager, T> work);
}
