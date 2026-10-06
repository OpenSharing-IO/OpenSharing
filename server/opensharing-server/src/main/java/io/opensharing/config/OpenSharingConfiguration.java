package io.opensharing.config;

import io.opensharing.catalog.CatalogConnector;
import io.opensharing.runtime.OpenSharing;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Builds the standalone server's {@link OpenSharing} from its Spring beans. */
@Configuration
public class OpenSharingConfiguration {

  @Bean
  public OpenSharing openSharing(CatalogConnector catalog) {
    return OpenSharing.builder().catalog(catalog).build();
  }
}
