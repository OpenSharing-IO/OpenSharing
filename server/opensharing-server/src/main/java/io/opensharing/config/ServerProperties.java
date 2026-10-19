package io.opensharing.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * The standalone server's settings, read from {@code server.properties} on the classpath. A JVM
 * system property with the same name as a key overrides it, then an environment variable with that
 * name or its upper snake case form, such as {@code SERVER_PORT} for {@code server.port}. A blank
 * value anywhere means unset.
 */
public class ServerProperties {

  private static final String RESOURCE = "server.properties";

  private final Properties properties = new Properties(defaults());
  private final Map<String, String> env;

  public enum Property {
    PORT("server.port", "8080"),
    CATALOG_TYPE("catalog.type", "local"),
    LOCAL_CATALOG_FILE("catalog.local.file", "classpath:local-catalog.yml");

    private final String key;
    private final String defaultValue;

    Property(String key, String defaultValue) {
      this.key = key;
      this.defaultValue = defaultValue;
    }
  }

  public ServerProperties() {
    try (InputStream in = ServerProperties.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in != null) {
        properties.load(in);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read " + RESOURCE, e);
    }
    dropBlankValues();
    env = System.getenv();
  }

  /** Settings read from {@code values} instead of {@code server.properties}. */
  public ServerProperties(Properties values) {
    this(values, System.getenv());
  }

  ServerProperties(Properties values, Map<String, String> env) {
    properties.putAll(values);
    dropBlankValues();
    this.env = env;
  }

  public String get(Property property) {
    String value = System.getProperty(property.key);
    if (isBlank(value)) {
      value = env.get(property.key);
    }
    if (isBlank(value)) {
      value = env.get(property.key.toUpperCase(Locale.ROOT).replaceAll("[.-]", "_"));
    }
    return isBlank(value) ? properties.getProperty(property.key) : value;
  }

  public int getPort() {
    String port = get(Property.PORT);
    try {
      return Integer.parseInt(port.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "invalid value '" + port + "' for " + Property.PORT.key + "; expected a port number");
    }
  }

  private static Properties defaults() {
    Properties defaults = new Properties();
    for (Property property : Property.values()) {
      defaults.setProperty(property.key, property.defaultValue);
    }
    return defaults;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private void dropBlankValues() {
    properties.entrySet().removeIf(entry -> entry.getValue().toString().isBlank());
  }
}
