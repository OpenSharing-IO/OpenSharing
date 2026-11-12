package io.opensharing.recipient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opensharing.Transactions;
import io.opensharing.auth.Privilege;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.exception.CatalogAuthorizationException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RecipientServiceTest {

  @Test
  void createsARecipientOnlyWithCreateRecipientFromTheCatalog() {
    List<Privilege> privileges = new ArrayList<>();
    CatalogConnector catalog =
        proxy(
            CatalogConnector.class,
            (proxy, method, args) -> {
              privileges.add((Privilege) args[1]);
              throw new CatalogAuthorizationException("no CREATE_RECIPIENT");
            });
    Transactions unused =
        proxy(
            Transactions.class,
            (proxy, method, args) -> {
              throw new UnsupportedOperationException();
            });
    RecipientService recipients =
        new RecipientService(
            catalog,
            null,
            new RecipientStore(unused),
            "http://host/activations",
            "http://host/sharing");
    UserContext alice = UserContext.fromUserIdAndName("alice-id", "alice");

    assertThrows(
        CatalogAuthorizationException.class,
        () ->
            recipients.create(
                alice,
                new CreateRecipientRequest("acme", null, AuthenticationType.TOKEN, 30L)));
    assertEquals(List.of(Privilege.CREATE_RECIPIENT), privileges);
  }

  @Test
  void listsEveryRecipientWithoutAskingTheCatalog() {
    CatalogConnector catalog =
        proxy(
            CatalogConnector.class,
            (proxy, method, args) -> {
              throw new AssertionError("asked the catalog");
            });
    List<RecipientEntity> rows = List.of(recipient("acme", "alice-id"), recipient("bolt", "bob"));
    AtomicInteger calls = new AtomicInteger();
    // The first transaction reads the page; each after it reads one recipient's activation code.
    Transactions tx =
        proxy(
            Transactions.class,
            (proxy, method, args) -> calls.getAndIncrement() == 0 ? rows : "code");
    RecipientService recipients =
        new RecipientService(
            catalog,
            null,
            new RecipientStore(tx),
            "http://host/activations",
            "http://host/sharing");

    List<RecipientResponse> items = recipients.listAll(null, null).items();
    assertEquals(List.of("acme", "bolt"), items.stream().map(RecipientResponse::name).toList());
    assertEquals("http://host/activations/code", items.get(0).activationUrl());
  }

  private static RecipientEntity recipient(String name, String owner) {
    RecipientEntity recipient = new RecipientEntity();
    recipient.setName(name);
    recipient.setOwnerId(owner);
    return recipient;
  }

  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return type.cast(
        Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
  }
}
