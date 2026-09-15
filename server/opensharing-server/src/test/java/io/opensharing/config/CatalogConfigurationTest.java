package io.opensharing.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opensharing.auth.AuthContext;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.Asset;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.DataSourceFormat;
import io.opensharing.catalog.TableProperties;
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
  @Autowired private OpenSharingProperties properties;

  @Test
  void loadsTheConfiguredLocalCatalog() {
    assertEquals(OpenSharingProperties.Hosting.Mode.STANDALONE, properties.getHosting().getMode());

    UserContext user = UserContext.fromUserIdAndName("alice@example.com", "alice@example.com");

    assertEquals("local", catalog.name());
    TableProperties table =
        (TableProperties)
            catalog
                .resolveAsset(new Asset(AssetType.TABLE, "main.sales.table1"), AuthContext.of(user))
                .additionalProperties();
    assertEquals(DataSourceFormat.DELTA, table.dataSourceFormat());
  }
}
