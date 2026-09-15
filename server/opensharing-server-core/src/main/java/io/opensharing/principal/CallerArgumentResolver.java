package io.opensharing.principal;

import io.opensharing.http.ApiException;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Hands controllers the principal established by the admin authentication filter. */
public class CallerArgumentResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return Caller.class.equals(parameter.getParameterType());
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    Object caller = webRequest.getAttribute(Caller.REQUEST_ATTRIBUTE, 0);
    if (caller instanceof Caller resolved) {
      return resolved;
    }
    throw ApiException.unauthenticated("this endpoint requires a principal's bearer token");
  }
}
