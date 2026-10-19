package io.opensharing;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Server;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.local.LocalCatalogConnector;
import io.opensharing.catalog.local.LocalCatalogLoader;
import io.opensharing.config.ServerProperties;
import io.opensharing.config.ServerProperties.Property;
import io.opensharing.runtime.OpenSharing;
import java.util.Locale;

/**
 * Runnable standalone OpenSharing server. It builds {@link OpenSharing} from {@link
 * ServerProperties} and listens for HTTP with Armeria.
 */
public final class OpenSharingServer implements AutoCloseable {

  private final Server server;
  private final OpenSharing openSharing;

  private OpenSharingServer(Server server, OpenSharing openSharing) {
    this.server = server;
    this.openSharing = openSharing;
  }

  /**
   * Starts the server with the settings in {@code server.properties}, overridden by JVM system
   * properties and environment variables, and blocks until the JVM shuts down. It takes no program
   * arguments.
   */
  public static void main(String[] args) throws InterruptedException {
    if (args.length > 0) {
      throw new IllegalArgumentException(
          "program arguments are not supported; set server.properties keys as -D JVM options or"
              + " environment variables");
    }
    OpenSharingServer server = start(new ServerProperties());
    server.server.closeOnJvmShutdown();
    server.server.blockUntilShutdown();
  }

  /**
   * Builds the server from {@code properties} and returns once it is listening. The catalog is
   * loaded first, so an invalid catalog fails startup before the port is bound.
   */
  public static OpenSharingServer start(ServerProperties properties) {
    OpenSharing openSharing = OpenSharing.builder().catalog(catalog(properties)).build();
    Server server =
        Server.builder()
            .http(properties.getPort())
            // Armeria refuses to start without a service; routes nothing serves are not found.
            .serviceUnder("/", (ctx, req) -> HttpResponse.of(HttpStatus.NOT_FOUND))
            .build();
    try {
      server.start().join();
    } catch (RuntimeException e) {
      server.close();
      throw e;
    }
    return new OpenSharingServer(server, openSharing);
  }

  /** The OpenSharing instance built from the server's settings. */
  public OpenSharing openSharing() {
    return openSharing;
  }

  /** Stops the server and blocks until it has stopped. */
  @Override
  public void close() {
    server.stop().join();
  }

  /**
   * The catalog named by {@code catalog.type}, matched ignoring case and surrounding spaces. Only
   * the local catalog ships with this build; it reads {@code catalog.local.file}.
   */
  static CatalogConnector catalog(ServerProperties properties) {
    String type = properties.get(Property.CATALOG_TYPE);
    return switch (type.trim().toLowerCase(Locale.ROOT)) {
      case LocalCatalogConnector.NAME ->
          new LocalCatalogConnector(
              LocalCatalogLoader.load(properties.get(Property.LOCAL_CATALOG_FILE)));
      default ->
          throw new IllegalStateException(
              "unknown catalog.type '" + type + "'; this build ships '"
                  + LocalCatalogConnector.NAME + "'");
    };
  }
}
