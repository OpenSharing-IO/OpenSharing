package io.opensharing.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opensharing.config.ServerProperties.Property;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerPropertiesTest {

  @TempDir Path dir;

  @Test
  void keepsTheDefaultsWithoutAFile() {
    ServerProperties properties = new ServerProperties(dir.resolve("missing.properties").toString());

    assertEquals(8080, properties.getPort());
    assertEquals("local", properties.get(Property.CATALOG_TYPE));
    assertEquals("classpath:local-catalog.yml", properties.get(Property.LOCAL_CATALOG_FILE));
  }

  @Test
  void readsTheFileAndKeepsTheDefaultOfBlankKeys() throws IOException {
    Path file = dir.resolve("server.properties");
    Files.writeString(file, "server.port=9090\ncatalog.local.file=\n");

    ServerProperties properties = new ServerProperties(file.toString());

    assertEquals(9090, properties.getPort());
    assertEquals("classpath:local-catalog.yml", properties.get(Property.LOCAL_CATALOG_FILE));
  }

  @Test
  void letsSystemPropertiesOverrideTheFile() {
    ServerProperties properties = new ServerProperties(props("server.port", "9090"));
    System.setProperty("server.port", "7070");
    try {
      assertEquals(7070, properties.getPort());
    } finally {
      System.clearProperty("server.port");
    }
  }

  @Test
  void rejectsAPortThatIsNotANumber() {
    ServerProperties properties = new ServerProperties(props("server.port", "eighty"));

    IllegalArgumentException invalid =
        assertThrows(IllegalArgumentException.class, properties::getPort);
    assertEquals(
        "invalid value 'eighty' for server.port; expected a port number", invalid.getMessage());
  }

  private static Properties props(String key, String value) {
    Properties props = new Properties();
    props.setProperty(key, value);
    return props;
  }
}
