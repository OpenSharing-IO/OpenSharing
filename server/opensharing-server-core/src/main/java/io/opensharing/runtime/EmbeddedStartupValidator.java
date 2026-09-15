package io.opensharing.runtime;

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

  private final SharingRuntime runtime;

  EmbeddedStartupValidator(SharingRuntime runtime) {
    this.runtime = runtime;
  }

  @Override
  public void run(ApplicationArguments args) {
    log.info(
        "OpenSharing is embedded; using '{}' catalog connector from the host",
        runtime.catalogConnector().name());
  }
}
