package io.opensharing.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opensharing.auth.AdminAuthenticationFilter;
import io.opensharing.catalog.CatalogConnector;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class ProviderConfiguration {

  @Bean
  FilterRegistrationBean<AdminAuthenticationFilter> adminAuthentication(
      CatalogConnector catalog, ObjectMapper objectMapper, OpenSharingProperties properties) {
    FilterRegistrationBean<AdminAuthenticationFilter> registration =
        new FilterRegistrationBean<>(new AdminAuthenticationFilter(catalog, objectMapper));
    registration.addUrlPatterns(properties.getProvider().getBasePath() + "/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }
}
