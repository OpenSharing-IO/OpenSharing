package io.opensharing.config;

import io.opensharing.auth.UserContextArgumentResolver;
import io.opensharing.recipient.RecipientPrincipalArgumentResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Lets provider controllers receive the authenticated user context. */
@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new UserContextArgumentResolver());
    resolvers.add(new RecipientPrincipalArgumentResolver());
  }
}
