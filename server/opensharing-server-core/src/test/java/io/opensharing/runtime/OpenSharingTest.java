package io.opensharing.runtime;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opensharing.catalog.CatalogConnector;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

class OpenSharingTest {

  @Test
  void refusesToBuildWithoutACatalogConnector() {
    assertThrows(IllegalStateException.class, () -> OpenSharing.builder().build());
  }

  @Test
  void buildsWithTheHostCatalog() {
    CatalogConnector catalog =
        (CatalogConnector)
            Proxy.newProxyInstance(
                CatalogConnector.class.getClassLoader(),
                new Class<?>[] {CatalogConnector.class},
                (proxy, method, args) -> {
                  throw new UnsupportedOperationException();
                });

    assertSame(catalog, OpenSharing.builder().catalog(catalog).build().catalog());
  }
}
