package io.opensharing.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.local.LocalCatalogConnector;
import io.opensharing.catalog.local.LocalCatalogLoader;
import io.opensharing.runtime.HostingMode;
import io.opensharing.runtime.SharingRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:opensharing-embedded-catalog;DB_CLOSE_DELAY=-1",
      "opensharing.hosting.mode=embedded",
      "opensharing.catalog.type=local",
      "opensharing.catalog.local.file=classpath:local-catalog.yml"
    })
class CatalogConfigurationEmbeddedTest {

  @Autowired private SharingRuntime runtime;
  @Autowired private ApplicationContext context;

  @Test
  void skipsStandaloneCatalogWiringWhenEmbedded() {
    assertEquals(HostingMode.EMBEDDED, runtime.hostingMode());
    assertEquals(LocalCatalogConnector.NAME, runtime.catalogConnector().name());
    assertFalse(context.containsBean("catalogConfiguration"));
  }

  @TestConfiguration
  static class HostCatalog {

    @Bean
    CatalogConnector catalogConnector() {
      try (InputStream in = HostCatalog.class.getResourceAsStream("/local-catalog.yml")) {
        return new LocalCatalogConnector(
            LocalCatalogLoader.load(in, "classpath:local-catalog.yml"));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
