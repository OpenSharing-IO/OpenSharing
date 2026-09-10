package io.opensharing.runtime;

import io.opensharing.OpenSharingApplication;
import io.opensharing.catalog.CatalogConnector;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * Library entry point for running OpenSharing standalone or embedding it in a host such as Unity
 * Catalog OSS.
 */
public final class OpenSharing {

  private OpenSharing() {}

  /** Starts the reference server in standalone mode (default deployment). */
  public static ConfigurableApplicationContext runStandalone(String... args) {
    return SpringApplication.run(OpenSharingApplication.class, args);
  }

  /** Configures an embedded assembly the host starts inside its own process. */
  public static EmbeddedBuilder embedded() {
    return new EmbeddedBuilder();
  }

  public static final class EmbeddedBuilder {

    private CatalogConnector catalog;
    private ProviderIdentityResolver identityResolver;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    private EmbeddedBuilder() {}

    /** In-process catalog integration supplied by the host (required). */
    public EmbeddedBuilder catalog(CatalogConnector catalog) {
      this.catalog = catalog;
      return this;
    }

    /**
     * Maps the host's authenticated principal to a {@link io.opensharing.principal.Caller} for
     * provider-admin APIs (required): embedded mode keeps no principal store or configured list of
     * its own to fall back to, so there is no admin identity without one.
     */
    public EmbeddedBuilder identityResolver(ProviderIdentityResolver identityResolver) {
      this.identityResolver = identityResolver;
      return this;
    }

    public EmbeddedBuilder property(String key, Object value) {
      properties.put(key, value);
      return this;
    }

    public ConfigurableApplicationContext run(String... args) {
      if (catalog == null) {
        throw new IllegalStateException("embedded OpenSharing requires a CatalogConnector from the host");
      }
      if (identityResolver == null) {
        throw new IllegalStateException(
            "embedded OpenSharing requires a ProviderIdentityResolver from the host");
      }
      Map<String, Object> merged = new LinkedHashMap<>(properties);
      merged.put("opensharing.hosting.mode", "embedded");
      java.util.ArrayList<String> allArgs = new java.util.ArrayList<>();
      for (Map.Entry<String, Object> entry : merged.entrySet()) {
        allArgs.add("--" + entry.getKey() + "=" + entry.getValue());
      }
      java.util.Collections.addAll(allArgs, args);
      return new SpringApplicationBuilder(OpenSharingApplication.class)
          .properties(merged)
          .initializers(new EmbeddedInitializer(catalog, identityResolver))
          .run(allArgs.toArray(String[]::new));
    }
  }

  private static final class EmbeddedInitializer
      implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private final CatalogConnector catalog;
    private final ProviderIdentityResolver identityResolver;

    private EmbeddedInitializer(CatalogConnector catalog, ProviderIdentityResolver identityResolver) {
      this.catalog = catalog;
      this.identityResolver = identityResolver;
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
      DefaultListableBeanFactory beanFactory =
          (DefaultListableBeanFactory) context.getBeanFactory();
      beanFactory.registerSingleton("catalogConnector", catalog);
      beanFactory.registerResolvableDependency(CatalogConnector.class, catalog);
      beanFactory.registerSingleton("providerIdentityResolver", identityResolver);
      beanFactory.registerResolvableDependency(ProviderIdentityResolver.class, identityResolver);
    }
  }
}
