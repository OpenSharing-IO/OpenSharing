package io.opensharing.runtime;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opensharing.Transactions;
import io.opensharing.catalog.CatalogConnector;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

class OpenSharingTest {

  @Test
  void refusesToBuildWithoutACatalogConnector() {
    assertThrows(IllegalStateException.class, () -> OpenSharing.builder().build());
  }

  @Test
  void refusesToBuildWithoutTransactions() {
    assertThrows(
        IllegalStateException.class,
        () -> OpenSharing.builder().catalog(unused(CatalogConnector.class)).build());
  }

  @Test
  void buildsWithTheHostCatalog() {
    CatalogConnector catalog = unused(CatalogConnector.class);

    OpenSharing openSharing =
        OpenSharing.builder().catalog(catalog).transactions(unused(Transactions.class)).build();

    assertSame(catalog, openSharing.catalog());
  }

  private static <T> T unused(Class<T> type) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              throw new UnsupportedOperationException();
            }));
  }
}
