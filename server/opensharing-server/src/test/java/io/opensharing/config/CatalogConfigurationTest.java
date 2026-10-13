package io.opensharing.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opensharing.OpenSharingServer;
import io.opensharing.auth.AuthContext;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.Asset;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.DataSourceFormat;
import io.opensharing.catalog.TableProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogConfigurationTest {

  @Test
  void loadsTheConfiguredLocalCatalog() {
    CatalogConnector catalog =
        CatalogConfiguration.catalogConnector(settings().getOpensharing());
    UserContext user = UserContext.fromUserIdAndName("alice@example.com", "alice@example.com");

    assertEquals("local", catalog.name());
    TableProperties table =
        (TableProperties)
            catalog
                .resolveAsset(new Asset(AssetType.TABLE, "main.sales.table1"), AuthContext.of(user))
                .additionalProperties();
    assertEquals(DataSourceFormat.DELTA, table.dataSourceFormat());
  }

  @Test
  void rejectsAMissingLocalCatalogFile() {
    ServerSettings settings = settings("--opensharing.catalog.local.file=classpath:missing.yml");

    IllegalStateException missing =
        assertThrows(
            IllegalStateException.class,
            () -> CatalogConfiguration.catalogConnector(settings.getOpensharing()));
    assertEquals("local catalog file 'classpath:missing.yml' does not exist", missing.getMessage());
  }

  @Test
  void buildsOpenSharingWithTheConfiguredCatalog() {
    try (OpenSharingServer server = OpenSharingServer.start(settings())) {
      assertEquals("local", server.openSharing().catalog().name());
    }
  }

  private static ServerSettings settings(String... overrides) {
    String[] args = new String[overrides.length + 1];
    args[0] = "--server.port=0";
    System.arraycopy(overrides, 0, args, 1, overrides.length);
    return ServerSettings.load(args, Map.of());
  }
}
