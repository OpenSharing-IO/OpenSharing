package io.opensharing.runtime;

import io.opensharing.catalog.CatalogConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Logs the host-supplied catalog when OpenSharing is embedded. */
@Component
@ConditionalOnHostingMode(HostingMode.EMBEDDED)
class EmbeddedStartupValidator implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(EmbeddedStartupValidator.class);

  private final CatalogConnector catalog;

  EmbeddedStartupValidator(CatalogConnector catalog) {
    this.catalog = catalog;
  }

  @Override
  public void run(ApplicationArguments args) {
    log.info(
        "OpenSharing is embedded; using '{}' catalog connector from the host", catalog.name());
  }
}
