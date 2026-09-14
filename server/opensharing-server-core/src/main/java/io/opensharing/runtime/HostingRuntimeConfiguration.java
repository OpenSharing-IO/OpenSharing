package io.opensharing.runtime;

import io.opensharing.catalog.CatalogConnector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
class HostingRuntimeConfiguration {

  @Bean
  SharingRuntime sharingRuntime(Environment environment, CatalogConnector catalogConnector) {
    String raw = environment.getProperty("opensharing.hosting.mode", "standalone");
    HostingMode mode = HostingMode.valueOf(raw.trim().toUpperCase());
    return new DefaultSharingRuntime(mode, catalogConnector);
  }
}
