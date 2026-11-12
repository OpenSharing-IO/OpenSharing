package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import io.opensharing.Transactions;
import io.opensharing.auth.AuthContext;
import io.opensharing.auth.Privilege;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.Asset;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.CredentialRequest;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.http.ApiJson;
import io.opensharing.http.ErrorCodes;
import io.opensharing.http.ErrorResponse;
import io.opensharing.runtime.OpenSharing;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class OpenSharingRecipientApiTest {

  @Test
  void servesTheProtocolOnAPortBasedVirtualHost() throws IOException {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    ServerBuilder builder = Server.builder().http(port);
    OpenSharing openSharing =
        OpenSharing.builder()
            .catalog(new UnusedCatalog())
            .transactions(new UnusedDatabase())
            .publicUrl("http://127.0.0.1:" + port)
            .protocolBasePath("/sharing")
            .activationBasePath("/sharing/activations")
            .build();
    OpenSharingRecipientApi.mount(builder.virtualHost(port), openSharing);
    Server server = builder.build();
    server.start().join();
    try {
      AggregatedHttpResponse response =
          WebClient.of("http://127.0.0.1:" + port).get("/sharing/shares").aggregate().join();
      assertEquals(401, response.status().code());
      assertEquals(
          ErrorCodes.UNAUTHENTICATED,
          ApiJson.MAPPER.readValue(response.contentUtf8(), ErrorResponse.class).errorCode());
    } finally {
      server.stop().join();
    }
  }

  private static final class UnusedCatalog implements CatalogConnector {
    @Override
    public String name() {
      return "unused";
    }

    @Override
    public ResolvedAsset resolveAsset(Asset asset, AuthContext auth) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<StorageCredentials> getStorageCredentials(
        CredentialRequest request, AuthContext auth) {
      throw new UnsupportedOperationException();
    }

    @Override
    public UserContext authorize(AuthContext auth, Privilege privilege) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class UnusedDatabase implements Transactions {
    @Override
    public <T> T inTransaction(boolean readOnly, Function<EntityManager, T> work) {
      throw new UnsupportedOperationException();
    }
  }
}
