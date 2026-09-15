package io.opensharing.runtime;

import io.opensharing.catalog.CatalogConnector;
import io.opensharing.config.OpenSharingProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class HostingRuntimeConfiguration {

  @Bean
  SharingRuntime sharingRuntime(OpenSharingProperties properties, CatalogConnector catalogConnector) {
    HostingMode mode = properties.getHosting().getMode();
    return new DefaultSharingRuntime(mode == null ? HostingMode.STANDALONE : mode, catalogConnector);
  }
}
