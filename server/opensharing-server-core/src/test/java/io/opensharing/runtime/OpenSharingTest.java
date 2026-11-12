package io.opensharing.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opensharing.Transactions;
import io.opensharing.auth.AuthContext;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.exception.CatalogAuthorizationException;
import io.opensharing.recipient.AuthenticationType;
import io.opensharing.recipient.CreateRecipientRequest;
import io.opensharing.share.CreateShareRequest;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenSharingTest {

  private static final UserContext ALICE = UserContext.fromUserIdAndName("alice-id", "alice");

  @Test
  void requiresAValidPublicUrl() {
    IllegalArgumentException missing =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                OpenSharing.builder()
                    .catalog(refusing(new ArrayList<>()))
                    .transactions(unused())
                    .build());
    assertEquals(
        "publicUrl is required; set it to the address recipients reach this server at",
        missing.getMessage());
    IllegalArgumentException invalid =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                OpenSharing.builder()
                    .catalog(refusing(new ArrayList<>()))
                    .transactions(unused())
                    .publicUrl("ftp://sharing.example.com")
                    .build());
    assertEquals(
        "invalid value 'ftp://sharing.example.com' for publicUrl; expected an http or https URL",
        invalid.getMessage());
  }

  @Test
  void namesTheConfiguredServerInEveryCatalogCall() {
    List<AuthContext> calls = new ArrayList<>();
    OpenSharing openSharing =
        OpenSharing.builder()
            .catalog(refusing(calls))
            .serverId("sharing-server-1")
            .transactions(unused())
            .publicUrl("https://sharing.example.com/")
            .build();

    createShareAndRecipient(openSharing);

    assertEquals(2, calls.size());
    calls.forEach(auth -> assertEquals("sharing-server-1", auth.serverId()));
  }

  @Test
  void namesNoServerUnlessTheHostSetsOne() {
    List<AuthContext> calls = new ArrayList<>();
    OpenSharing openSharing =
        OpenSharing.builder()
            .catalog(refusing(calls))
            .transactions(unused())
            .publicUrl("https://sharing.example.com")
            .build();

    createShareAndRecipient(openSharing);

    assertEquals(2, calls.size());
    calls.forEach(auth -> assertNull(auth.serverId()));
  }

  // Both ask the catalog for a privilege first, which it refuses before any database work.
  private static void createShareAndRecipient(OpenSharing openSharing) {
    assertThrows(
        CatalogAuthorizationException.class,
        () ->
            openSharing.shares().create(ALICE, new CreateShareRequest("sales", null, null, null)));
    assertThrows(
        CatalogAuthorizationException.class,
        () ->
            openSharing
                .recipients()
                .create(
                    ALICE,
                    new CreateRecipientRequest("acme", null, AuthenticationType.TOKEN, 30L)));
  }

  private static CatalogConnector refusing(List<AuthContext> calls) {
    return proxy(
        CatalogConnector.class,
        (proxy, method, args) -> {
          calls.add((AuthContext) args[0]);
          throw new CatalogAuthorizationException("refused");
        });
  }

  private static Transactions unused() {
    return proxy(
        Transactions.class,
        (proxy, method, args) -> {
          throw new UnsupportedOperationException();
        });
  }

  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return type.cast(
        Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
  }
}
