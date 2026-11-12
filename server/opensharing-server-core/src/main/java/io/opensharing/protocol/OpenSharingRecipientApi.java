package io.opensharing.protocol;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.VirtualHostAnnotatedServiceBindingBuilder;
import com.linecorp.armeria.server.VirtualHostBuilder;
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
   * Serves the sharing protocol under {@code openSharing}'s protocol base path, behind recipient
   * bearer tokens, and recipient activation under its activation base path, behind none. Both bring
   * their own JSON converters and error handling, which take precedence over the host's
   * server-wide ones.
   *
   * @param host the virtual host that serves the host's API: {@link
   *     ServerBuilder#defaultVirtualHost()} on a server without virtual hosts. A request on a port
   *     with a port-based virtual host is not served from the default one.
   * @param openSharing the instance whose paths and public URL the endpoints are served with
   */
  public static void mount(VirtualHostBuilder host, OpenSharing openSharing) {
    ApiExceptionHandler errors = ApiExceptionHandler.forRecipients();
    RecipientTokenDecorator tokens = new RecipientTokenDecorator(openSharing.recipients());
    protocol(host, openSharing.protocolBasePath(), errors, tokens)
        .build(
            new OpenSharingProtocolService(
                openSharing.protocolShares(),
                openSharing.protocolSchemas(),
                openSharing.protocolTables()));
    protocol(host, openSharing.protocolBasePath(), errors, tokens)
        .build(new DeltaSharingProtocolService(openSharing.protocolTables()));
    host.annotatedService()
        .pathPrefix(openSharing.activationBasePath())
        .exceptionHandlers(errors)
        .decorator(errors)
        .build(new ActivationService(openSharing.recipients()));
  }

  private static VirtualHostAnnotatedServiceBindingBuilder protocol(
      VirtualHostBuilder host,
      String basePath,
      ApiExceptionHandler errors,
      RecipientTokenDecorator tokens) {
    // The token check must sit inside the error decorator, so that its 401s get the protocol body.
    return host.annotatedService()
        .pathPrefix(basePath)
        .requestConverters(new JacksonRequestConverterFunction(ApiJson.MAPPER))
        .responseConverters(new JacksonResponseConverterFunction(ApiJson.MAPPER))
        .exceptionHandlers(errors)
        .decorator(tokens)
        .decorator(errors);
  }
}
