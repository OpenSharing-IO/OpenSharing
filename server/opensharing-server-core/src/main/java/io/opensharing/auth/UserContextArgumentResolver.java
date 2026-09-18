package io.opensharing.auth;

import io.opensharing.http.ApiException;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Injects the authenticated {@link UserContext} into provider controller methods. */
public class UserContextArgumentResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return UserContext.class.equals(parameter.getParameterType());
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    Object user =
        webRequest.getAttribute(ProviderAuthenticationFilter.USER_CONTEXT_ATTRIBUTE, 0);
    if (user instanceof UserContext resolved) {
      return resolved;
    }
    throw ApiException.unauthenticated("this endpoint requires an authenticated user");
  }
}
