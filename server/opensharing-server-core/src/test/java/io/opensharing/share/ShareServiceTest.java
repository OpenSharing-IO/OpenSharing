package io.opensharing.share;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opensharing.Transactions;
import io.opensharing.asset.SharedDataObjectStore;
import io.opensharing.auth.Privilege;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.exception.CatalogAuthorizationException;
import io.opensharing.recipient.RecipientStore;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShareServiceTest {

  @Test
  void createsAShareOnlyWithCreateShareFromTheCatalog() {
    List<Privilege> privileges = new ArrayList<>();
    CatalogConnector catalog =
        proxy(
            CatalogConnector.class,
            (proxy, method, args) -> {
              privileges.add((Privilege) args[1]);
              throw new CatalogAuthorizationException("no CREATE_SHARE");
            });
    Transactions unused =
        proxy(
            Transactions.class,
            (proxy, method, args) -> {
              throw new UnsupportedOperationException();
            });
    ShareService shares =
        new ShareService(
            catalog,
            null,
            unused,
            new ShareStore(unused),
            new SharedDataObjectStore(unused),
            new SharePermissionStore(unused),
            new RecipientStore(unused));
    UserContext alice = UserContext.fromUserIdAndName("alice-id", "alice");

    assertThrows(
        CatalogAuthorizationException.class,
        () -> shares.create(alice, new CreateShareRequest("sales", null, null, null)));
    assertEquals(List.of(Privilege.CREATE_SHARE), privileges);
  }

  @Test
  void listsEveryShareWithoutAskingTheCatalog() {
    CatalogConnector catalog =
        proxy(
            CatalogConnector.class,
            (proxy, method, args) -> {
              throw new AssertionError("asked the catalog");
            });
    List<ShareEntity> rows = List.of(share("alice_share", "alice-id"), share("bob_share", "bob"));
    Transactions tx = proxy(Transactions.class, (proxy, method, args) -> rows);
    ShareService shares =
        new ShareService(
            catalog,
            null,
            tx,
            new ShareStore(tx),
            new SharedDataObjectStore(tx),
            new SharePermissionStore(tx),
            new RecipientStore(tx));

    assertEquals(
        List.of("alice_share", "bob_share"),
        shares.listAll(null, null).items().stream().map(ShareResponse::name).toList());
  }

  private static ShareEntity share(String name, String owner) {
    ShareEntity share = new ShareEntity();
    share.setName(name);
    share.setOwnerId(owner);
    return share;
  }

  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return type.cast(
        Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
  }
}
