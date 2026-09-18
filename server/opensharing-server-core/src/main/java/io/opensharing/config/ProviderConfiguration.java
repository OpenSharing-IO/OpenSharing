package io.opensharing.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opensharing.auth.ProviderAuthenticationFilter;
import io.opensharing.catalog.CatalogConnector;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Mounts {@link ProviderAuthenticationFilter} on the provider API path only. */
@Configuration
public class ProviderConfiguration {

  @Bean
  FilterRegistrationBean<ProviderAuthenticationFilter> providerAuthentication(
      CatalogConnector catalog, ObjectMapper objectMapper, OpenSharingProperties properties) {
    FilterRegistrationBean<ProviderAuthenticationFilter> registration =
        new FilterRegistrationBean<>(
            new ProviderAuthenticationFilter(
                catalog, objectMapper, properties.getProvider().getBasePath()));
    registration.addUrlPatterns(properties.getProvider().getBasePath() + "/*");
    // After Boot's encoding / forwarded-header / request-context filters (HIGHEST … +5).
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }
}
