package io.opensharing;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.JacksonRequestConverterFunction;
import com.linecorp.armeria.server.annotation.JacksonResponseConverterFunction;
import io.opensharing.auth.ProviderTokenDecorator;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.local.LocalCatalogConnector;
import io.opensharing.catalog.local.LocalCatalogLoader;
import io.opensharing.config.ServerProperties;
import io.opensharing.config.ServerProperties.Property;
import io.opensharing.http.ApiExceptionHandler;
import io.opensharing.http.ApiJson;
import io.opensharing.persistence.JpaTransactions;
import io.opensharing.protocol.OpenSharingRecipientApi;
import io.opensharing.recipient.RecipientAdminService;
import io.opensharing.runtime.OpenSharing;
import io.opensharing.share.ShareAdminService;
import java.util.List;
import java.util.Locale;

/**
 * Runnable standalone OpenSharing server. It assembles {@link OpenSharing} from {@link
 * ServerProperties} and serves it over HTTP with Armeria. An embedding host skips this class and
 * builds {@link OpenSharing} itself.
 */
public final class OpenSharingServer implements AutoCloseable {

  private final Server server;
  private final JpaTransactions transactions;
  private final OpenSharing openSharing;

  private OpenSharingServer(Server server, JpaTransactions transactions, OpenSharing openSharing) {
    this.server = server;
    this.transactions = transactions;
    this.openSharing = openSharing;
  }

  /**
   * Starts the server from {@code server.properties}, where JVM system properties and environment
   * variables override keys, and serves until the JVM shuts down.
   */
  public static void main(String[] args) throws InterruptedException {
    OpenSharingServer server = start(new ServerProperties());
    Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    server.server.blockUntilShutdown();
  }

  /**
   * Starts a server configured by {@code properties} and returns once it is listening. Provider
   * services are served under {@code provider.base-path}, the sharing protocol under {@code
   * protocol.base-path} behind recipient tokens, recipient activation under {@code
   * activation.base-path} behind none, and any other path gets a 404 in the protocol's error
   * format. Links handed to recipients are built on {@code server.public-url}, which must be set.
   * If startup fails, the database connections are closed.
   */
  public static OpenSharingServer start(ServerProperties properties) {
    String publicUrl = properties.getPublicUrl();
    CatalogConnector catalog = catalog(properties);
    JpaTransactions transactions = new JpaTransactions(OpenSharing.entities());
    try {
      OpenSharing openSharing =
          OpenSharing.builder()
              .catalog(catalog)
              .transactions(transactions)
              .build();
      ApiExceptionHandler errors = new ApiExceptionHandler();
      HttpService notFound =
          (ctx, req) -> {
            throw HttpStatusException.of(HttpStatus.NOT_FOUND);
          };
      ServerBuilder builder =
          Server.builder()
              .http(properties.getPort())
              .annotatedServiceExtensions(
                  List.of(new JacksonRequestConverterFunction(ApiJson.MAPPER)),
                  List.of(new JacksonResponseConverterFunction(ApiJson.MAPPER)),
                  List.of(errors))
              .serviceUnder("/", notFound.decorate(errors));
      String providerPath = properties.get(Property.PROVIDER_BASE_PATH);
      ProviderTokenDecorator providerToken = new ProviderTokenDecorator(catalog);
      mountProvider(
          builder,
          providerPath,
          providerToken,
          errors,
          new ShareAdminService(openSharing.shares()));
      mountProvider(
          builder,
          providerPath,
          providerToken,
          errors,
          new RecipientAdminService(
              openSharing.recipients(),
              publicUrl + properties.get(Property.ACTIVATION_BASE_PATH)));
      OpenSharingRecipientApi.mount(
          builder,
          publicUrl,
          properties.get(Property.PROTOCOL_BASE_PATH),
          properties.get(Property.ACTIVATION_BASE_PATH),
          openSharing);
      Server server = builder.build();
      server.start().join();
      return new OpenSharingServer(server, transactions, openSharing);
    } catch (RuntimeException | Error e) {
      transactions.close();
      throw e;
    }
  }

  /** The port the server listens on, which it picks itself when configured with 0. */
  public int port() {
    return server.activeLocalPort();
  }

  /** The core services behind the HTTP endpoints, for callers such as tests that need them. */
  public OpenSharing openSharing() {
    return openSharing;
  }

  /** The database transactions behind the core services, for callers such as tests. */
  public Transactions transactions() {
    return transactions;
  }

  /** Stops the server, then closes the database connections its requests used. */
  @Override
  public void close() {
    server.stop().join();
    transactions.close();
  }

  // Serves an annotated provider service under the provider path, behind the provider token check.
  // The token check must sit inside the error decorator, so that its 401s get the protocol body.
  private static void mountProvider(
      ServerBuilder builder,
      String providerPath,
      ProviderTokenDecorator providerToken,
      ApiExceptionHandler errors,
      Object service) {
    builder
        .annotatedService()
        .pathPrefix(providerPath)
        .decorator(providerToken)
        .decorator(errors)
        .build(service);
  }

  /**
   * Builds the catalog connector named by {@code catalog.type}, in any case. This build ships only
   * {@code local}, and fails on any other type.
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
