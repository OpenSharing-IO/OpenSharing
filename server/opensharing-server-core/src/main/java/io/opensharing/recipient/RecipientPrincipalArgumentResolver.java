package io.opensharing.recipient;

import io.opensharing.http.ApiException;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Hands controllers the recipient established by the authentication filter. */
public class RecipientPrincipalArgumentResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return RecipientPrincipal.class.equals(parameter.getParameterType());
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    Object principal = webRequest.getAttribute(RecipientPrincipal.REQUEST_ATTRIBUTE, 0);
    if (principal instanceof RecipientPrincipal recipient) {
      return recipient;
    }
    throw ApiException.unauthenticated("a bearer token is required");
  }
}
