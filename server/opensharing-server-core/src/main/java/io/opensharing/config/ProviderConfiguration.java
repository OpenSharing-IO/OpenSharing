package io.opensharing.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opensharing.auth.AdminAuthenticationFilter;
import io.opensharing.principal.ConfiguredPrincipalsIdentityResolver;
import io.opensharing.principal.ProviderIdentityResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class ProviderConfiguration {

  @Bean
  @ConditionalOnMissingBean(ProviderIdentityResolver.class)
  ProviderIdentityResolver providerIdentityResolver(OpenSharingProperties properties) {
    return new ConfiguredPrincipalsIdentityResolver(properties.getAdmin().getPrincipals());
  }

  @Bean
  FilterRegistrationBean<AdminAuthenticationFilter> adminAuthentication(
      ProviderIdentityResolver identityResolver,
      ObjectMapper objectMapper,
      OpenSharingProperties properties) {
    FilterRegistrationBean<AdminAuthenticationFilter> registration =
        new FilterRegistrationBean<>(
            new AdminAuthenticationFilter(identityResolver, objectMapper));
    registration.addUrlPatterns(properties.getProvider().getBasePath() + "/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }
}
