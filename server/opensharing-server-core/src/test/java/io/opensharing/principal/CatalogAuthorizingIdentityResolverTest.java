package io.opensharing.principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.catalog.AssetLookup;
import io.opensharing.catalog.CatalogAuthorizationException;
import io.opensharing.catalog.CatalogCaller;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.CatalogPrincipal;
import io.opensharing.catalog.CredentialRequest;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentials;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Against a fake {@link CatalogConnector} that only ever implements {@link
 * CatalogConnector#authorize}, so that what is under test is this resolver's own request handling —
 * extracting the bearer token, picking a privilege from the method and path — and not any one real
 * connector's HTTP client. {@link io.opensharing.catalog.unity.UnityCatalogConnectorTest} covers
 * Unity Catalog's own implementation of {@link CatalogConnector#authorize}.
 */
class CatalogAuthorizingIdentityResolverTest {

  @Test
  void resolvesAKnownBearerTokenToItsCaller() {
    FakeConnector connector =
        new FakeConnector((token, privilege) -> new CatalogPrincipal("alice-id", "alice@example.com"));
    CatalogAuthorizingIdentityResolver resolver = new CatalogAuthorizingIdentityResolver(connector);

    Optional<Caller> resolved = resolver.resolve(request("GET", "/api/2.1/opensharing/provider/shares", "Bearer alice-token"));

    assertTrue(resolved.isPresent());
    assertEquals("alice-id", resolved.get().principalId());
    assertEquals("alice@example.com", resolved.get().name());
    assertEquals("alice-token", resolved.get().bearerToken());
  }

  @Test
  void refusesARequestWithNoBearerTokenWithoutAskingTheConnector() {
    FakeConnector connector = new FakeConnector((token, privilege) -> new CatalogPrincipal("id", "name"));
    CatalogAuthorizingIdentityResolver resolver = new CatalogAuthorizingIdentityResolver(connector);

    assertTrue(
        resolver.resolve(request("GET", "/api/2.1/opensharing/provider/shares", null)).isEmpty());
    assertNull(connector.lastToken, "nothing to ask the connector about");
  }

  @Test
  void refusesWhateverTheConnectorRefuses() {
    FakeConnector connector =
        new FakeConnector(
            (token, privilege) -> {
              throw new CatalogAuthorizationException("no");
            });
    CatalogAuthorizingIdentityResolver resolver = new CatalogAuthorizingIdentityResolver(connector);

    assertTrue(
        resolver
            .resolve(request("POST", "/api/2.1/opensharing/provider/shares", "Bearer mallory-token"))
            .isEmpty());
  }

  @Test
  void asksForCreateShareOnlyWhenCreatingAShare() {
    FakeConnector connector = new FakeConnector((token, privilege) -> new CatalogPrincipal("id", "name"));
    CatalogAuthorizingIdentityResolver resolver = new CatalogAuthorizingIdentityResolver(connector);

    resolver.resolve(request("POST", "/api/2.1/opensharing/provider/shares", "Bearer a-token"));
    assertEquals("CREATE_SHARE", connector.lastPrivilege);

    resolver.resolve(request("POST", "/api/2.1/opensharing/provider/recipients", "Bearer a-token"));
    assertEquals("CREATE_RECIPIENT", connector.lastPrivilege);

    resolver.resolve(request("PATCH", "/api/2.1/opensharing/provider/shares/sales", "Bearer a-token"));
    assertNull(
        connector.lastPrivilege, "every other provider-admin request resolves identity only");

    resolver.resolve(request("GET", "/api/2.1/opensharing/provider/shares", "Bearer a-token"));
    assertNull(connector.lastPrivilege);
  }

  private static MockHttpServletRequest request(String method, String uri, String authorizationHeader) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod(method);
    request.setRequestURI(uri);
    if (authorizationHeader != null) {
      request.addHeader("Authorization", authorizationHeader);
    }
    return request;
  }

  @FunctionalInterface
  private interface Authorize {
    CatalogPrincipal apply(String bearerToken, String privilege);
  }

  private static final class FakeConnector implements CatalogConnector {

    private final Authorize authorize;
    private String lastToken;
    private String lastPrivilege;

    FakeConnector(Authorize authorize) {
      this.authorize = authorize;
    }

    @Override
    public String name() {
      return "fake";
    }

    @Override
    public ResolvedAsset resolveAsset(AssetLookup lookup, CatalogCaller caller) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<StorageCredentials> getStorageCredentials(CredentialRequest request, CatalogCaller caller) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CatalogPrincipal authorize(String bearerToken, String privilege) {
      this.lastToken = bearerToken;
      this.lastPrivilege = privilege;
      return authorize.apply(bearerToken, privilege);
    }
  }
}
