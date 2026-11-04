package io.opensharing.protocol;

import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.VirtualHostBuilder;
import com.linecorp.armeria.server.annotation.JacksonRequestConverterFunction;
import com.linecorp.armeria.server.annotation.JacksonResponseConverterFunction;
import io.opensharing.http.ApiExceptionHandler;
import io.opensharing.http.ApiJson;
import io.opensharing.runtime.OpenSharing;
import java.util.List;
import java.util.function.Function;

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
   * @param host the virtual host that serves the host's API: {@link
   *     ServerBuilder#defaultVirtualHost()} on a server without virtual hosts. A request on a port
   *     with a port-based virtual host is not served from the default one.
   * @param publicUrl the address recipients reach the host at, such as {@code
   *     https://sharing.example.com}, which profile files point recipients to
   */
  public static void mount(
      VirtualHostBuilder host,
      String publicUrl,
      String protocolBasePath,
      String activationBasePath,
      OpenSharing openSharing) {
    // Registered through annotatedService(String, Object, Function, Iterable) rather than the
    // fluent binding builder, whose setters erase to different return types across Armeria
    // releases: chained calls compiled against the version here fail on a host's newer Armeria.
    ApiExceptionHandler errors = ApiExceptionHandler.forRecipients();
    RecipientTokenDecorator tokens = new RecipientTokenDecorator(openSharing.recipients());
    // The token check must sit inside the error decorator, so that its 401s get the protocol body.
    Function<? super HttpService, ? extends HttpService> protocolDecorators =
        service -> service.decorate(tokens).decorate(errors);
    List<Object> protocolOptions =
        List.of(
            new JacksonRequestConverterFunction(ApiJson.MAPPER),
            new JacksonResponseConverterFunction(ApiJson.MAPPER),
            errors);
    host.annotatedService(
        protocolBasePath,
        new OpenSharingProtocolService(
            openSharing.protocolShares(),
            openSharing.protocolSchemas(),
            openSharing.protocolTables()),
        protocolDecorators,
        protocolOptions);
    host.annotatedService(
        protocolBasePath,
        new DeltaSharingProtocolService(openSharing.protocolTables()),
        protocolDecorators,
        protocolOptions);
    host.annotatedService(
        activationBasePath,
        new ActivationService(openSharing.recipients(), publicUrl + protocolBasePath),
        service -> service.decorate(errors),
        List.of(errors));
  }
}
