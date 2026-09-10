package io.opensharing.config;

import io.opensharing.runtime.ConditionalOnHostingMode;
import io.opensharing.runtime.HostingMode;
import io.opensharing.runtime.ProviderIdentityResolver;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.CatalogException;
import io.opensharing.catalog.local.LocalCatalogConnector;
import io.opensharing.catalog.local.LocalCatalogFile;
import io.opensharing.catalog.local.LocalCatalogLoader;
import io.opensharing.catalog.unity.UnityCatalogConnector;
import io.opensharing.principal.CatalogAuthorizingIdentityResolver;
import io.opensharing.principal.ConfiguredPrincipalsIdentityResolver;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Selects the {@link CatalogConnector} the server runs with. Two ship: the file-backed one for local
 * development, and Unity Catalog. A deployment that talks to something else contributes its own
 * {@code CatalogConnector} bean, which takes precedence over this one.
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
          case UnityCatalogConnector.NAME -> unityConnector(catalog.getUnity());
          default ->
              throw new IllegalStateException(
                  "unknown opensharing.catalog.type '"
                      + catalog.getType()
                      + "'; this build ships '"
                      + LocalCatalogConnector.NAME
                      + "' and '"
                      + UnityCatalogConnector.NAME
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

  /**
   * The url is checked here rather than on the first request, so that a server pointed at nothing —
   * or at a host with no scheme, which is the easy mistake — says so at startup instead of when a
   * recipient first reads a table.
   */
  private CatalogConnector unityConnector(OpenSharingProperties.Catalog.Unity config) {
    String uri = config.getUri() == null ? "" : config.getUri().trim();
    if (uri.isBlank()) {
      throw new IllegalStateException(
          "opensharing.catalog.unity.uri is required to run with the '"
              + UnityCatalogConnector.NAME
              + "' connector, e.g. http://localhost:8081/api/2.1/unity-catalog");
    }
    URI parsed;
    try {
      parsed = URI.create(uri);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          "opensharing.catalog.unity.uri '" + uri + "' is not a url", e);
    }
    if (!parsed.isAbsolute() || parsed.getHost() == null) {
      throw new IllegalStateException(
          "opensharing.catalog.unity.uri '" + uri + "' is not an absolute http or https url");
    }
    // Each request appends its own path and query to this, so anything already carrying one would
    // build a url with the parts in the wrong order — a query before a path, or a fragment before
    // both — and every request would go somewhere unintended. Said here, because there is no request
    // to blame it on and nothing about the url changes between startup and the first read.
    if (parsed.getQuery() != null || parsed.getFragment() != null) {
      throw new IllegalStateException(
          "opensharing.catalog.unity.uri '"
              + uri
              + "' must be a base url with no query or fragment, since each request appends its own");
    }
    return new UnityCatalogConnector(
        parsed, config.getConnectTimeout(), config.getRequestTimeout(), config.getServerSecret());
  }

  /**
   * Resolves a provider-admin request's caller: by asking the catalog connector's own {@link
   * CatalogConnector#authorize} whose token this is ({@code unity} — the same call embedded mode
   * makes, just against a remote address instead of a loopback one), or against a fixed, configured
   * list ({@code local}, whose connector has no such notion). A deployment that talks to something
   * else contributes its own {@code ProviderIdentityResolver} bean, which takes precedence over this
   * one.
   *
   * <p>Depends on the {@link CatalogConnector} bean itself, rather than building a connection of its
   * own from {@code opensharing.catalog.unity.uri}, so that {@code unity} shares one connector's
   * connection to Unity Catalog instead of opening a second one to the same place.
   */
  @Bean
  @ConditionalOnMissingBean(ProviderIdentityResolver.class)
  public ProviderIdentityResolver providerIdentityResolver(
      CatalogConnector catalogConnector, OpenSharingProperties properties) {
    OpenSharingProperties.Catalog catalog = properties.getCatalog();
    String type = catalog.getType() == null ? "" : catalog.getType().trim().toLowerCase(Locale.ROOT);
    return switch (type) {
      case LocalCatalogConnector.NAME ->
          new ConfiguredPrincipalsIdentityResolver(properties.getAdmin().getPrincipals());
      case UnityCatalogConnector.NAME -> new CatalogAuthorizingIdentityResolver(catalogConnector);
      default ->
          throw new IllegalStateException("unknown opensharing.catalog.type '" + catalog.getType() + "'");
    };
  }
}
