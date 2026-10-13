package io.opensharing;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Server;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.config.CatalogConfiguration;
import io.opensharing.config.OpenSharingConfiguration;
import io.opensharing.config.ServerSettings;
import io.opensharing.runtime.OpenSharing;

/** Runnable standalone OpenSharing server. */
public final class OpenSharingServer implements AutoCloseable {

  private final Server server;
  private final OpenSharing openSharing;

  private OpenSharingServer(Server server, OpenSharing openSharing) {
    this.server = server;
    this.openSharing = openSharing;
  }

  public static void main(String[] args) throws InterruptedException {
    OpenSharingServer server = start(ServerSettings.load(args, System.getenv()));
    server.server.closeOnJvmShutdown();
    server.server.blockUntilShutdown();
  }

  /** Builds the server from {@code settings} and returns once it is listening. */
  public static OpenSharingServer start(ServerSettings settings) {
    CatalogConnector catalog = CatalogConfiguration.catalogConnector(settings.getOpensharing());
    OpenSharing openSharing = OpenSharingConfiguration.openSharing(catalog);
    Server server =
        Server.builder()
            .http(settings.getServer().getPort())
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
}
