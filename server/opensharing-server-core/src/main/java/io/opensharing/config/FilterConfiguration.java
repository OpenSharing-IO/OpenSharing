package io.opensharing.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opensharing.auth.AdminAuthenticationFilter;
import io.opensharing.recipient.RecipientAuthenticationFilter;
import io.opensharing.recipient.RecipientTokenService;
import io.opensharing.runtime.ProviderIdentityResolver;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Mounts authentication in front of the two request surfaces: recipient bearer tokens on the
 * protocol endpoints, principal bearer tokens on the provider-admin endpoints. Activation URLs are
 * deliberately left unauthenticated — the nonce in the URL is the credential.
 */
@Configuration
public class FilterConfiguration {

  @Bean
  public FilterRegistrationBean<RecipientAuthenticationFilter> recipientAuthentication(
      RecipientTokenService tokenService,
      ObjectMapper objectMapper,
      OpenSharingProperties properties) {
    FilterRegistrationBean<RecipientAuthenticationFilter> registration =
        new FilterRegistrationBean<>(
            new RecipientAuthenticationFilter(
                tokenService,
                objectMapper,
                // The provider and activation APIs may be mounted under the protocol prefix
                // (they are, by default), which would otherwise also match this filter's URL
                // pattern.
                List.of(
                    properties.getProvider().getBasePath(),
                    properties.getActivation().getBasePath())));
    registration.addUrlPatterns(properties.getProtocolPrefix() + "/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }

  @Bean
  public FilterRegistrationBean<AdminAuthenticationFilter> adminAuthentication(
      ProviderIdentityResolver identityResolver,
      ObjectMapper objectMapper,
      OpenSharingProperties properties) {
    FilterRegistrationBean<AdminAuthenticationFilter> registration =
        new FilterRegistrationBean<>(new AdminAuthenticationFilter(identityResolver, objectMapper));
    registration.addUrlPatterns(properties.getProvider().getBasePath() + "/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }
}
