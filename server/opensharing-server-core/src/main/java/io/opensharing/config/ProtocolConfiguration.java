package io.opensharing.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opensharing.recipient.RecipientAuthenticationFilter;
import io.opensharing.recipient.RecipientStore;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Mounts recipient bearer authentication on protocol endpoints only. */
@Configuration
public class ProtocolConfiguration {

  @Bean
  FilterRegistrationBean<RecipientAuthenticationFilter> recipientAuthentication(
      RecipientStore recipients, ObjectMapper objectMapper, OpenSharingProperties properties) {
    var filter =
        new RecipientAuthenticationFilter(
            recipients,
            objectMapper,
            List.of(properties.getProvider().getBasePath(), properties.getActivationPrefix()));
    var registration = new FilterRegistrationBean<>(filter);
    registration.addUrlPatterns(properties.getProtocolPrefix() + "/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }
}
