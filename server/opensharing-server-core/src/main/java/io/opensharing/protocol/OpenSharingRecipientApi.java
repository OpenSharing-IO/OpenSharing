package io.opensharing.protocol;

import com.linecorp.armeria.server.AnnotatedServiceBindingBuilder;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.JacksonRequestConverterFunction;
import com.linecorp.armeria.server.annotation.JacksonResponseConverterFunction;
import io.opensharing.http.ApiExceptionHandler;
import io.opensharing.http.ApiJson;
import io.opensharing.runtime.OpenSharing;

/**
 * The recipient-facing endpoints, which OpenSharing authenticates itself. A host mounts them on
 * its Armeria server next to its own services.
 */
public final class OpenSharingRecipientApi {

  private OpenSharingRecipientApi() {}

  /**
   * Serves the sharing protocol under {@code protocolBasePath}, behind recipient bearer tokens, and
   * recipient activation under {@code activationBasePath}, behind none. Both bring their own JSON
   * converters and error handling, which take precedence over the host's server-wide ones.
   *
   * @param publicUrl the address recipients reach the host at, such as {@code
   *     https://sharing.example.com}, which profile files point recipients to
   */
  public static void mount(
      ServerBuilder builder,
      String publicUrl,
      String protocolBasePath,
      String activationBasePath,
      OpenSharing openSharing) {
    ApiExceptionHandler errors = new ApiExceptionHandler();
    RecipientTokenDecorator tokens = new RecipientTokenDecorator(openSharing.recipients());
    protocol(builder, protocolBasePath, errors, tokens)
        .build(
            new OpenSharingProtocolService(
                openSharing.protocolShares(),
                openSharing.protocolSchemas(),
                openSharing.protocolTables()));
    protocol(builder, protocolBasePath, errors, tokens)
        .build(new DeltaSharingProtocolService(openSharing.protocolTables()));
    builder
        .annotatedService()
        .pathPrefix(activationBasePath)
        .exceptionHandlers(errors)
        .decorator(errors)
        .build(new ActivationService(openSharing.recipients(), publicUrl + protocolBasePath));
  }

  private static AnnotatedServiceBindingBuilder protocol(
      ServerBuilder builder,
      String basePath,
      ApiExceptionHandler errors,
      RecipientTokenDecorator tokens) {
    // The token check must sit inside the error decorator, so that its 401s get the protocol body.
    return builder
        .annotatedService()
        .pathPrefix(basePath)
        .requestConverters(new JacksonRequestConverterFunction(ApiJson.MAPPER))
        .responseConverters(new JacksonResponseConverterFunction(ApiJson.MAPPER))
        .exceptionHandlers(errors)
        .decorator(tokens)
        .decorator(errors);
  }
}
