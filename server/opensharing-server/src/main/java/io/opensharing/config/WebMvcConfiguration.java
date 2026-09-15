package io.opensharing.config;

import io.opensharing.auth.CallerArgumentResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Lets controller methods take a {@link io.opensharing.auth.Caller} from the authenticated request. */
@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new CallerArgumentResolver());
  }
}
