package io.opensharing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opensharing.auth.AuthContext;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.Asset;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.DataSourceFormat;
import io.opensharing.catalog.TableProperties;
import io.opensharing.config.ServerProperties;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class OpenSharingServerTest {

  @Test
  void loadsTheConfiguredLocalCatalog() {
    CatalogConnector catalog = OpenSharingServer.catalog(properties());
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
    ServerProperties properties = properties("catalog.local.file", "classpath:missing.yml");

    IllegalStateException missing =
        assertThrows(IllegalStateException.class, () -> OpenSharingServer.catalog(properties));
    assertEquals("local catalog file 'classpath:missing.yml' does not exist", missing.getMessage());
  }

  @Test
  void rejectsAnUnknownCatalogType() {
    ServerProperties properties = properties("catalog.type", "glue");

    IllegalStateException unknown =
        assertThrows(IllegalStateException.class, () -> OpenSharingServer.catalog(properties));
    assertEquals("unknown catalog.type 'glue'; this build ships 'local'", unknown.getMessage());
  }

  @Test
  void buildsOpenSharingWithTheConfiguredCatalog() {
    try (OpenSharingServer server = OpenSharingServer.start(properties())) {
      assertEquals("local", server.openSharing().catalog().name());
    }
  }

  private static ServerProperties properties(String... overrides) {
    Properties props = new Properties();
    props.setProperty("server.port", "0");
    for (int i = 0; i < overrides.length; i += 2) {
      props.setProperty(overrides[i], overrides[i + 1]);
    }
    return new ServerProperties(props);
  }
}
