package io.opensharing.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.opensharing.catalog.AssetLookup;
import io.opensharing.catalog.CatalogCaller;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.CredentialRequest;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.runtime.HostingMode;
import io.opensharing.runtime.SharingRuntime;
import java.util.List;
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
    assertEquals("host", runtime.catalogConnector().name());
    assertFalse(context.containsBean("catalogConfiguration"));
  }

  @TestConfiguration
  static class HostCatalog {

    @Bean
    CatalogConnector catalogConnector() {
      return new CatalogConnector() {
        @Override
        public String name() {
          return "host";
        }

        @Override
        public ResolvedAsset resolveAsset(AssetLookup lookup, CatalogCaller caller) {
          throw new UnsupportedOperationException();
        }

        @Override
        public List<StorageCredentials> getStorageCredentials(
            CredentialRequest request, CatalogCaller caller) {
          return List.of();
        }
      };
    }
  }
}
