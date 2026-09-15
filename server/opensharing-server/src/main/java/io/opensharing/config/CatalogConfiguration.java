package io.opensharing.config;

import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.local.LocalCatalogConnector;
import io.opensharing.catalog.local.LocalCatalogFile;
import io.opensharing.catalog.local.LocalCatalogLoader;
import io.opensharing.exception.CatalogException;
import io.opensharing.runtime.ConditionalOnHostingMode;
import io.opensharing.runtime.HostingMode;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/** Configures the catalog used by the standalone server. Embedded hosts supply their own bean. */
@Configuration
@ConditionalOnHostingMode(HostingMode.STANDALONE)
public class CatalogConfiguration {

  @Bean
  @ConditionalOnMissingBean(CatalogConnector.class)
  public CatalogConnector catalogConnector(
      OpenSharingProperties properties, ResourceLoader resourceLoader) {
    OpenSharingProperties.Catalog catalog = properties.getCatalog();
    String type = catalog.getType() == null ? "" : catalog.getType().trim().toLowerCase(Locale.ROOT);
    return switch (type) {
      case LocalCatalogConnector.NAME -> localConnector(catalog.getLocal(), resourceLoader);
      default ->
          throw new IllegalStateException(
              "unknown opensharing.catalog.type '"
                  + catalog.getType()
                  + "'; this build ships '"
                  + LocalCatalogConnector.NAME
                  + "'");
    };
  }

  private CatalogConnector localConnector(
      OpenSharingProperties.Catalog.Local config, ResourceLoader resourceLoader) {
    String location = config.getFile();
    Resource resource = resourceLoader.getResource(location);
    if (!resource.exists()) {
      throw new IllegalStateException("local catalog file '" + location + "' does not exist");
    }
    try (InputStream in = resource.getInputStream()) {
      LocalCatalogFile file = LocalCatalogLoader.load(in, location);
      return new LocalCatalogConnector(file);
    } catch (IOException e) {
      throw new CatalogException("failed to read local catalog file " + location, e);
    }
  }
}
