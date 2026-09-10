package io.opensharing.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.StubCatalogConnector;
import io.opensharing.principal.Caller;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:opensharing-embedded;DB_CLOSE_DELAY=-1",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "opensharing.hosting.mode=embedded"
    })
@AutoConfigureMockMvc
class EmbeddedOpenSharingTest {

  @Autowired private MockMvc mvc;
  @Autowired private SharingRuntime runtime;

  @Test
  void bootsEmbeddedWithHostSuppliedCatalogAndIdentity() throws Exception {
    assertEquals(HostingMode.EMBEDDED, runtime.hostingMode());
    assertEquals("stub", runtime.catalogConnector().name());

    mvc.perform(
            get("/api/2.1/opensharing/provider/shares").header("Authorization", "Bearer host-token"))
        .andExpect(status().isOk());
  }

  @TestConfiguration
  static class HostBeans {

    @Bean
    CatalogConnector catalogConnector() {
      return StubCatalogConnector.INSTANCE;
    }

    @Bean
    ProviderIdentityResolver providerIdentityResolver() {
      return request -> Optional.of(new Caller("host-principal-id", "uc-admin@example.com", "host-token"));
    }
  }
}
