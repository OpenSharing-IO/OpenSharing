package io.opensharing.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ServerSettingsTest {

  @Test
  void readsDefaultsFromApplicationYml() {
    ServerSettings settings = ServerSettings.load(new String[0], Map.of());

    assertEquals(8080, settings.getServer().getPort());
    assertEquals("local", settings.getOpensharing().getCatalog().getType());
    assertEquals(
        "classpath:local-catalog.yml", settings.getOpensharing().getCatalog().getLocal().getFile());
  }

  @Test
  void overridesKeysFromTheEnvironment() {
    ServerSettings settings =
        ServerSettings.load(
            new String[0],
            Map.of("SERVER_PORT", "9090", "OPENSHARING_CATALOG_LOCAL_FILE", "/tmp/catalog.yml"));

    assertEquals(9090, settings.getServer().getPort());
    assertEquals("/tmp/catalog.yml", settings.getOpensharing().getCatalog().getLocal().getFile());
  }

  @Test
  void letsArgumentsOverrideTheEnvironment() {
    ServerSettings settings =
        ServerSettings.load(new String[] {"--server.port=7070"}, Map.of("SERVER_PORT", "9090"));

    assertEquals(7070, settings.getServer().getPort());
  }

  @Test
  void rejectsArgumentsThatAreNotKeyValuePairs() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ServerSettings.load(new String[] {"server.port=7070"}, Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> ServerSettings.load(new String[] {"--server.port"}, Map.of()));
  }

  @Test
  void rejectsValuesOfTheWrongType() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ServerSettings.load(new String[] {"--server.port=eighty"}, Map.of()));
  }
}
