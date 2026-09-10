package io.opensharing.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opensharing.catalog.StubCatalogConnector;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

class OpenSharingEmbeddedBuilderTest {

  @Test
  void refusesToStartWithoutACatalogConnector() {
    assertThrows(
        IllegalStateException.class,
        () -> OpenSharing.embedded().identityResolver(request -> Optional.empty()).run());
  }

  @Test
  void refusesToStartWithoutAnIdentityResolver() {
    assertThrows(
        IllegalStateException.class,
        () -> OpenSharing.embedded().catalog(StubCatalogConnector.INSTANCE).run());
  }

  @Test
  void startsEmbeddedContextWithHostCatalog() {
    ConfigurableApplicationContext context =
        OpenSharing.embedded()
            .catalog(StubCatalogConnector.INSTANCE)
            .identityResolver(request -> Optional.empty())
            .property(
                "spring.datasource.url", "jdbc:h2:mem:opensharing-embedded-builder;DB_CLOSE_DELAY=-1")
            .property("spring.jpa.hibernate.ddl-auto", "create-drop")
            .property("server.port", "0")
            .property("spring.main.web-application-type", "none")
            .run();
    try {
      SharingRuntime runtime = context.getBean(SharingRuntime.class);
      assertEquals(HostingMode.EMBEDDED, runtime.hostingMode());
      assertEquals("stub", runtime.catalogConnector().name());
    } finally {
      context.close();
    }
  }
}
