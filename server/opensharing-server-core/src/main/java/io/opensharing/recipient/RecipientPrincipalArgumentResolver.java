package io.opensharing.recipient;

import io.opensharing.http.ApiException;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Injects the recipient authenticated by the protocol filter into controller methods. */
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
    Object principal =
        webRequest.getAttribute(RecipientPrincipal.REQUEST_ATTRIBUTE, NativeWebRequest.SCOPE_REQUEST);
    if (principal instanceof RecipientPrincipal recipient) {
      return recipient;
    }
    throw ApiException.unauthenticated("bearer token is required");
  }
}
