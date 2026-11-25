package io.opensharing.protocol;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.VirtualHostAnnotatedServiceBindingBuilder;
import com.linecorp.armeria.server.VirtualHostBuilder;
import com.linecorp.armeria.server.annotation.JacksonRequestConverterFunction;
import com.linecorp.armeria.server.annotation.JacksonResponseConverterFunction;
import io.opensharing.http.ApiExceptionHandler;
import io.opensharing.http.ApiJson;
import io.opensharing.protocol.iceberg.IcebergErrorHandler;
import io.opensharing.protocol.iceberg.IcebergNotFoundService;
import io.opensharing.protocol.iceberg.IcebergRestCatalogService;
import io.opensharing.runtime.OpenSharing;

/**
 * The recipient-facing endpoints, which OpenSharing authenticates itself. A host mounts them on
 * its Armeria server next to its own services.
 */
public final class OpenSharingRecipientApi {

  private OpenSharingRecipientApi() {}

  /**
   * Serves the sharing protocol under {@code openSharing}'s protocol base path, and the Iceberg
   * REST catalog under {@code iceberg} in it, behind recipient bearer tokens, and
   * recipient activation under its activation base path, behind none. Both bring
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
    IcebergErrorHandler icebergErrors = new IcebergErrorHandler();
    String icebergPath = icebergBasePath(openSharing.protocolBasePath());
    host.annotatedService()
        .pathPrefix(icebergPath)
        .requestConverters(new JacksonRequestConverterFunction(ApiJson.MAPPER))
        .responseConverters(new JacksonResponseConverterFunction(ApiJson.MAPPER))
        .exceptionHandlers(icebergErrors)
        .decorator(tokens)
        .decorator(icebergErrors)
        .build(
            new IcebergRestCatalogService(
                openSharing.protocolShares(),
                openSharing.protocolSchemas(),
                openSharing.protocolIcebergTables()));
    // Armeria routes what the catalog doesn't serve to the server-wide fallback, whose body
    // Iceberg clients can't parse.
    host.serviceUnder(
        icebergPath, new IcebergNotFoundService().decorate(tokens).decorate(icebergErrors));
    host.annotatedService()
        .pathPrefix(openSharing.activationBasePath())
        .exceptionHandlers(errors)
        .decorator(errors)
        .build(new ActivationService(openSharing.recipients()));
  }

  /** Where the Iceberg REST catalog is served: {@code iceberg} under the protocol endpoint. */
  static String icebergBasePath(String protocolBasePath) {
    return (protocolBasePath.endsWith("/") ? protocolBasePath : protocolBasePath + "/") + "iceberg";
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
