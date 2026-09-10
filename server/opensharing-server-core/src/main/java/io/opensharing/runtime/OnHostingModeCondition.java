package io.opensharing.runtime;

import java.util.Map;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

final class OnHostingModeCondition implements Condition {

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    Map<String, Object> attributes = metadata.getAnnotationAttributes(ConditionalOnHostingMode.class.getName());
    if (attributes == null) {
      return false;
    }
    HostingMode required = (HostingMode) attributes.get("value");
    String raw = context.getEnvironment().getProperty("opensharing.hosting.mode", "standalone");
    HostingMode actual = HostingMode.valueOf(raw.trim().toUpperCase());
    return required == actual;
  }
}
