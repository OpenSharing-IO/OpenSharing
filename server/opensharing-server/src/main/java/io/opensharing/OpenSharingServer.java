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

/** Runnable standalone OpenSharing server. */
public final class OpenSharingServer implements AutoCloseable {

  private final Server server;
  private final OpenSharing openSharing;

  private OpenSharingServer(Server server, OpenSharing openSharing) {
    this.server = server;
    this.openSharing = openSharing;
  }

  public static void main(String[] args) throws InterruptedException {
    OpenSharingServer server = start(new ServerProperties());
    server.server.closeOnJvmShutdown();
    server.server.blockUntilShutdown();
  }

  /** Builds the server from {@code properties} and returns once it is listening. */
  public static OpenSharingServer start(ServerProperties properties) {
    OpenSharing openSharing = OpenSharing.builder().catalog(catalog(properties)).build();
    Server server =
        Server.builder()
            .http(properties.getPort())
            // Armeria refuses to start without a service; routes nothing serves are not found.
            .serviceUnder("/", (ctx, req) -> HttpResponse.of(HttpStatus.NOT_FOUND))
            .build();
    server.start().join();
    return new OpenSharingServer(server, openSharing);
  }

  /** The port the server listens on, which it picks itself when configured with 0. */
  public int port() {
    return server.activeLocalPort();
  }

  public OpenSharing openSharing() {
    return openSharing;
  }

  @Override
  public void close() {
    server.stop().join();
  }

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
