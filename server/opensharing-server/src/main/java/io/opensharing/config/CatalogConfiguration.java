package io.opensharing.config;

import io.opensharing.runtime.ConditionalOnHostingMode;
import io.opensharing.runtime.HostingMode;
import io.opensharing.runtime.ProviderIdentityResolver;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.CatalogException;
import io.opensharing.catalog.local.LocalCatalogConnector;
import io.opensharing.catalog.local.LocalCatalogFile;
import io.opensharing.catalog.local.LocalCatalogLoader;
import io.opensharing.principal.ConfiguredPrincipalsIdentityResolver;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Configures the file-backed catalog for standalone development. Embedded hosts contribute their
 * own {@link CatalogConnector} bean.
 */
@Configuration
@ConditionalOnHostingMode(HostingMode.STANDALONE)
public class CatalogConfiguration {

  private static final Logger log = LoggerFactory.getLogger(CatalogConfiguration.class);

  @Bean
  @ConditionalOnMissingBean(CatalogConnector.class)
  public CatalogConnector catalogConnector(
      OpenSharingProperties properties, ResourceLoader resourceLoader) {
    OpenSharingProperties.Catalog catalog = properties.getCatalog();
    String type = catalog.getType() == null ? "" : catalog.getType().trim().toLowerCase(Locale.ROOT);
    CatalogConnector connector =
        switch (type) {
          case LocalCatalogConnector.NAME -> localConnector(catalog.getLocal(), resourceLoader);
          default ->
              throw new IllegalStateException(
                  "unknown opensharing.catalog.type '"
                      + catalog.getType()
                      + "'; this build ships '"
                      + LocalCatalogConnector.NAME
                      + "'");
        };
    log.info("Using '{}' catalog connector", connector.name());
    return connector;
  }

  private CatalogConnector localConnector(
      OpenSharingProperties.Catalog.Local config, ResourceLoader resourceLoader) {
    Resource resource = resourceLoader.getResource(config.getFile());
    if (!resource.exists()) {
      throw new IllegalStateException(
          "local catalog file '" + config.getFile() + "' does not exist");
    }
    try (InputStream in = resource.getInputStream()) {
      LocalCatalogFile file = LocalCatalogLoader.load(in, config.getFile());
      return new LocalCatalogConnector(file);
    } catch (IOException e) {
      throw new CatalogException("failed to read local catalog file " + config.getFile(), e);
    }
  }

  /** Resolves standalone provider identities from the configured local principal list. */
  @Bean
  @ConditionalOnMissingBean(ProviderIdentityResolver.class)
  public ProviderIdentityResolver providerIdentityResolver(
      OpenSharingProperties properties) {
    OpenSharingProperties.Catalog catalog = properties.getCatalog();
    String type = catalog.getType() == null ? "" : catalog.getType().trim().toLowerCase(Locale.ROOT);
    if (!LocalCatalogConnector.NAME.equals(type)) {
      throw new IllegalStateException(
          "unknown opensharing.catalog.type '" + catalog.getType() + "'");
    }
    return new ConfiguredPrincipalsIdentityResolver(properties.getAdmin().getPrincipals());
  }
}
