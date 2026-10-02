package io.opensharing.runtime;

import io.opensharing.OpenSharingApplication;
import io.opensharing.catalog.CatalogConnector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Starts OpenSharing inside a host process. */
public final class OpenSharing {

  private OpenSharing() {}

  /** Configures an embedded assembly the host starts inside its own process. */
  public static EmbeddedBuilder embedded() {
    return new EmbeddedBuilder();
  }

  public static final class EmbeddedBuilder {

    private CatalogConnector catalog;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    private EmbeddedBuilder() {}

    /** In-process catalog integration supplied by the host (required). */
    public EmbeddedBuilder catalog(CatalogConnector catalog) {
      this.catalog = catalog;
      return this;
    }

    public EmbeddedBuilder property(String key, Object value) {
      properties.put(key, value);
      return this;
    }

    public ConfigurableApplicationContext run(String... args) {
      if (catalog == null) {
        throw new IllegalStateException(
            "embedded OpenSharing requires a CatalogConnector from the host");
      }
      Map<String, Object> merged = new LinkedHashMap<>(properties);
      merged.put("opensharing.hosting.mode", "embedded");
      ArrayList<String> allArgs = new ArrayList<>();
      for (Map.Entry<String, Object> entry : merged.entrySet()) {
        allArgs.add("--" + entry.getKey() + "=" + entry.getValue());
      }
      Collections.addAll(allArgs, args);
      return new SpringApplicationBuilder(OpenSharingApplication.class)
          .properties(merged)
          .initializers(new EmbeddedInitializer(catalog))
          .run(allArgs.toArray(String[]::new));
    }
  }

  private static final class EmbeddedInitializer
      implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private final CatalogConnector catalog;

    private EmbeddedInitializer(CatalogConnector catalog) {
      this.catalog = catalog;
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
      DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) context.getBeanFactory();
      beanFactory.registerSingleton("catalogConnector", catalog);
      beanFactory.registerResolvableDependency(CatalogConnector.class, catalog);
    }
  }
}
