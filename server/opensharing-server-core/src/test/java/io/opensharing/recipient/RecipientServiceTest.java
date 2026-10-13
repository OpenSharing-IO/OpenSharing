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
    RecipientService recipients = new RecipientService(catalog, new RecipientStore(unused));
    UserContext alice = UserContext.fromUserIdAndName("alice-id", "alice");

    assertThrows(
        CatalogAuthorizationException.class,
        () ->
            recipients.create(
                alice,
                new CreateRecipientRequest("acme", null, AuthenticationType.TOKEN),
                "http://host/activations"));
    assertEquals(List.of(Privilege.CREATE_RECIPIENT), privileges);
  }

  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return type.cast(
        Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
  }
}
