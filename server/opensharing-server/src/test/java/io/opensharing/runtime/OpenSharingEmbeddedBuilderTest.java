package io.opensharing.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.local.LocalCatalogConnector;
import io.opensharing.catalog.local.LocalCatalogLoader;
import io.opensharing.config.OpenSharingProperties;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

class OpenSharingEmbeddedBuilderTest {

  @Test
  void refusesToStartWithoutACatalogConnector() {
    assertThrows(IllegalStateException.class, () -> OpenSharing.embedded().run());
  }

  @Test
  void startsEmbeddedContextWithHostCatalog() throws IOException {
    ConfigurableApplicationContext context =
        OpenSharing.embedded()
            .catalog(localCatalog())
            .property(
                "spring.datasource.url", "jdbc:h2:mem:opensharing-embedded-builder;DB_CLOSE_DELAY=-1")
            .property("spring.jpa.hibernate.ddl-auto", "create-drop")
            .property("server.port", "0")
            .property("spring.main.web-application-type", "none")
            .run();
    try {
      OpenSharingProperties properties = context.getBean(OpenSharingProperties.class);
      CatalogConnector catalog = context.getBean(CatalogConnector.class);
      assertEquals(HostingMode.EMBEDDED, properties.getHosting().getMode());
      assertEquals(LocalCatalogConnector.NAME, catalog.name());
    } finally {
      context.close();
    }
  }

  private static CatalogConnector localCatalog() throws IOException {
    try (InputStream in =
        OpenSharingEmbeddedBuilderTest.class.getResourceAsStream("/local-catalog.yml")) {
      return new LocalCatalogConnector(
          LocalCatalogLoader.load(in, "classpath:local-catalog.yml"));
    }
  }
}
