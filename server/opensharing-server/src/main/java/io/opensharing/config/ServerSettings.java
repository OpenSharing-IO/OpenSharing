package io.opensharing.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * The standalone server's settings. Defaults come from {@code application.yml}; an environment
 * variable overrides a key in it, and a {@code --key=value} argument overrides any key.
 */
public final class ServerSettings {

  private static final String DEFAULTS = "application.yml";

  private static final ObjectMapper MAPPER =
      new ObjectMapper(new YAMLFactory())
          .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
          .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private final Server server = new Server();
  private final OpenSharingProperties opensharing = new OpenSharingProperties();

  public Server getServer() {
    return server;
  }

  public OpenSharingProperties getOpensharing() {
    return opensharing;
  }

  /**
   * Reads {@code application.yml}, then applies {@code env} and {@code args}.
   *
   * <p>An environment variable names a key in {@code application.yml} in upper case, with dots as
   * underscores and dashes dropped: {@code OPENSHARING_CATALOG_TYPE} sets {@code
   * opensharing.catalog.type}.
   */
  public static ServerSettings load(String[] args, Map<String, String> env) {
    ObjectNode tree = defaults();
    applyEnvironment(tree, "", env);
    for (String arg : args) {
      int equals = arg.indexOf('=');
      if (!arg.startsWith("--") || equals < 3) {
        throw new IllegalArgumentException("expected --key=value, got '" + arg + "'");
      }
      set(tree, arg.substring(2, equals), arg.substring(equals + 1));
    }
    try {
      return MAPPER.treeToValue(tree, ServerSettings.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("invalid server settings: " + e.getOriginalMessage(), e);
    }
  }

  private static ObjectNode defaults() {
    try (InputStream in = ServerSettings.class.getClassLoader().getResourceAsStream(DEFAULTS)) {
      if (in == null) {
        throw new IllegalStateException(DEFAULTS + " is missing from the classpath");
      }
      return (ObjectNode) MAPPER.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read " + DEFAULTS, e);
    }
  }

  private static void applyEnvironment(ObjectNode node, String path, Map<String, String> env) {
    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      String key = path.isEmpty() ? field.getKey() : path + "." + field.getKey();
      if (field.getValue() instanceof ObjectNode child) {
        applyEnvironment(child, key, env);
      } else {
        String value = env.get(key.toUpperCase(Locale.ROOT).replace('.', '_').replace("-", ""));
        if (value != null) {
          field.setValue(JsonNodeFactory.instance.textNode(value));
        }
      }
    }
  }

  private static void set(ObjectNode tree, String key, String value) {
    String[] parts = key.split("\\.");
    ObjectNode node = tree;
    for (int i = 0; i < parts.length - 1; i++) {
      node = node.get(parts[i]) instanceof ObjectNode child ? child : node.putObject(parts[i]);
    }
    node.put(parts[parts.length - 1], value);
  }

  /** The HTTP listener. */
  public static class Server {

    /** Port to listen on; 0 picks a free one. */
    private int port = 8080;

    public int getPort() {
      return port;
    }

    public void setPort(int port) {
      this.port = port;
    }
  }
}
