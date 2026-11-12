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
   * Serves the paths configured on {@code openSharing}; hosts may use their own authentication for
   * provider endpoints while these recipient endpoints retain OpenSharing's token handling.
   *
   * @param host the virtual host serving these endpoints. Use {@link
   *     ServerBuilder#defaultVirtualHost()} on a server without virtual hosts.
   * @param openSharing the OpenSharing instance whose paths and services are served
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
