package io.opensharing.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Properties;

/**
 * The standalone server's settings, read from {@code server.properties} on the classpath. A JVM
 * system property or an environment variable with the same name as a key overrides it.
 */
public class ServerProperties {

  private static final String RESOURCE = "server.properties";

  private final Properties properties = new Properties(defaults());

  public enum Property {
    PORT("server.port", "8080"),
    PUBLIC_URL("server.public-url", null),
    PROVIDER_BASE_PATH("provider.base-path", "/api/1.0/opensharing/provider"),
    ACTIVATION_BASE_PATH("activation.base-path", "/api/1.0/opensharing/activations"),
    PROTOCOL_BASE_PATH("protocol.base-path", "/api/1.0/opensharing"),
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
  }

  public ServerProperties(Properties overrides) {
    properties.putAll(overrides);
    dropBlankValues();
  }

  public String get(Property property) {
    String value = System.getProperty(property.key);
    if (value == null) {
      value = System.getenv(property.key);
    }
    return value != null ? value : properties.getProperty(property.key);
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

  /**
   * The address recipients reach this server at, such as {@code https://sharing.example.com},
   * without a trailing slash. Activation links and the endpoint in profile files are built on it,
   * so it must be set: the address a request arrives on can be internal to a proxy.
   */
  public String getPublicUrl() {
    String value = get(Property.PUBLIC_URL);
    if (value == null) {
      throw new IllegalArgumentException(
          Property.PUBLIC_URL.key
              + " is required; set it to the address recipients reach this server at");
    }
    String url = value.trim();
    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException e) {
      uri = null;
    }
    if (uri == null
        || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost() == null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null) {
      throw new IllegalArgumentException(
          "invalid value '"
              + value
              + "' for "
              + Property.PUBLIC_URL.key
              + "; expected an http or https URL");
    }
    return url.replaceAll("/+$", "");
  }

  private static Properties defaults() {
    Properties defaults = new Properties();
    for (Property property : Property.values()) {
      if (property.defaultValue != null) {
        defaults.setProperty(property.key, property.defaultValue);
      }
    }
    return defaults;
  }

  // A blank value in server.properties means unset, so the default applies.
  private void dropBlankValues() {
    properties.entrySet().removeIf(entry -> entry.getValue().toString().isBlank());
  }
}
