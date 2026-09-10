package io.opensharing.runtime;

import io.opensharing.catalog.CatalogConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Fails fast when embedded mode is selected but the host did not register the beans it is expected
 * to supply.
 */
@Component
@ConditionalOnHostingMode(HostingMode.EMBEDDED)
class EmbeddedStartupValidator implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(EmbeddedStartupValidator.class);

  private final CatalogConnector catalog;
  private final ProviderIdentityResolver identityResolver;

  EmbeddedStartupValidator(
      CatalogConnector catalog,
      org.springframework.beans.factory.ObjectProvider<ProviderIdentityResolver> identityResolver) {
    this.catalog = catalog;
    this.identityResolver = identityResolver.getIfAvailable();
  }

  @Override
  public void run(ApplicationArguments args) {
    log.info(
        "OpenSharing is embedded; using '{}' catalog connector from the host",
        catalog.name());
    if (identityResolver == null) {
      log.warn(
          "No ProviderIdentityResolver bean is registered; provider-admin authentication still "
              + "expects principals provisioned from opensharing.admin.principals");
    }
  }
}
