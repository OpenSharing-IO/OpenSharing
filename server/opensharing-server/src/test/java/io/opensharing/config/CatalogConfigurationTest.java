package io.opensharing.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opensharing.auth.AuthContext;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.AssetLookup;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.TableFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:catalog-configuration;DB_CLOSE_DELAY=-1",
      "opensharing.catalog.type=local",
      "opensharing.catalog.local.file=classpath:local-catalog.yml"
    })
class CatalogConfigurationTest {

  @Autowired private CatalogConnector catalog;

  @Test
  void loadsTheConfiguredLocalCatalog() {
    UserContext user = new UserContext("alice@example.com", "alice@example.com");

    assertEquals("local", catalog.name());
    assertEquals(
        TableFormat.DELTA,
        catalog
            .resolveAsset(AssetLookup.of(AssetType.TABLE, "main.sales.table1"), AuthContext.of(user))
            .format());
  }
}
