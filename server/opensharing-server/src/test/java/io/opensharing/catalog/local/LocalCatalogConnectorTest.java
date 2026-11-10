package io.opensharing.catalog.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.auth.AuthContext;
import io.opensharing.auth.Privilege;
import io.opensharing.auth.UserContext;
import io.opensharing.exception.AssetAccessDeniedException;
import io.opensharing.catalog.Asset;
import io.opensharing.catalog.AssetPage;
import io.opensharing.exception.AssetNotFoundException;
import io.opensharing.catalog.AssetType;
import io.opensharing.exception.CatalogAuthenticationException;
import io.opensharing.exception.CatalogException;
import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.CredentialRequest;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.catalog.StorageOperation;
import io.opensharing.catalog.TableProperties;
import io.opensharing.catalog.DataSourceFormat;
import io.opensharing.exception.UnsupportedAssetTypeException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalCatalogConnectorTest {

  private static final String TABLE1 =
      "s3://delta-exchange-test/delta-exchange-test/table1/";

  private static final UserContext ALICE =
      UserContext.fromUserIdAndName("catalog-alice-id", "alice");
  private static final UserContext BOB = UserContext.fromUserIdAndName("catalog-bob-id", "bob");

  /** The sample catalog the server ships with. */
  private static LocalCatalogConnector sample() {
    try (InputStream in =
        LocalCatalogConnectorTest.class.getResourceAsStream("/local-catalog.yml")) {
      return new LocalCatalogConnector(LocalCatalogLoader.load(in, "classpath:local-catalog.yml"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static LocalCatalogConnector connector(String yaml) {
    return new LocalCatalogConnector(
        LocalCatalogLoader.load(
            new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test"));
  }

  @Test
  void resolvesTableWithFormat() {
    ResolvedAsset asset = resolve("main.sales.table1", ALICE);

    assertEquals(TABLE1, asset.location().storageLocation());
    assertEquals(
        new TableProperties(DataSourceFormat.DELTA, Map.of("subtype", "MANAGED")),
        asset.additionalProperties());
  }

  @Test
  void treatsAssetsWithoutAnExplicitTypeAsTables() {
    ResolvedAsset asset = resolve("main.finance.ledger", ALICE);

    assertEquals(AssetType.TABLE, asset.type());
    TableProperties table = (TableProperties) asset.additionalProperties();
    assertEquals(DataSourceFormat.DELTA, table.dataSourceFormat());
  }

  @Test
  void resolvesNamesCaseInsensitively() {
    assertEquals(TABLE1, resolve("MAIN.Sales.Table1", ALICE).location().storageLocation());
  }

  @Test
  void rejectsUnknownAsset() {
    LocalCatalogConnector connector = sample();
    Asset lookup = new Asset(AssetType.TABLE, "main.sales.missing");

    assertThrows(
        AssetNotFoundException.class,
        () -> connector.resolveAsset(lookup, AuthContext.of(ALICE)));
  }

  /**
   * Serving consults the same list, of the owner of the share being read through, so this is what
   * revokes an existing read as well as what refuses a new share. The list holds user ids; the
   * user name never grants access.
   */
  @Test
  void letsOnlyTheListedPrincipalsShareARestrictedAsset() {
    assertEquals(
        TABLE1, resolve("main.finance.ledger", ALICE).location().storageLocation());

    LocalCatalogConnector connector = sample();
    Asset lookup = new Asset(AssetType.TABLE, "main.finance.ledger");
    assertThrows(
        AssetAccessDeniedException.class,
        () -> connector.resolveAsset(lookup, AuthContext.of(BOB)));
  }

  @Test
  void matchesShareableByOnUserIdNotUserName() {
    LocalCatalogConnector connector = sample();
    Asset lookup = new Asset(AssetType.TABLE, "main.finance.ledger");
    UserContext impostor = UserContext.fromUserIdAndName("catalog-mallory-id", "alice");
    assertThrows(
        AssetAccessDeniedException.class,
        () -> connector.resolveAsset(lookup, AuthContext.of(impostor)));
  }

  @Test
  void refusesToVendWhenCallerIsNotOnShareableBy() {
    LocalCatalogConnector connector = sample();
    CredentialRequest request =
        new CredentialRequest(
            AssetType.TABLE,
            "main.finance.ledger",
            null,
            TABLE1,
            StorageOperation.READ,
            null);

    assertThrows(
        AssetAccessDeniedException.class,
        () -> connector.getStorageCredentials(request, AuthContext.of(BOB)));
  }

  @Test
  void refusesToVendUnknownAsset() {
    LocalCatalogConnector connector = sample();
    CredentialRequest request =
        new CredentialRequest(
            AssetType.TABLE,
            "main.sales.missing",
            null,
            TABLE1,
            StorageOperation.READ,
            null);

    assertThrows(
        AssetNotFoundException.class,
        () -> connector.getStorageCredentials(request, AuthContext.of(ALICE)));
  }

  @Test
  void refusesToVendALocationOutsideTheAsset() {
    LocalCatalogConnector connector = sample();
    CredentialRequest request =
        new CredentialRequest(
            AssetType.TABLE,
            "main.sales.table1",
            null,
            "s3://someone-elses-bucket/secret/",
            StorageOperation.READ,
            null);

    assertThrows(
        CatalogException.class,
        () -> connector.getStorageCredentials(request, AuthContext.of(ALICE)));
  }

  private static ResolvedAsset resolve(String identifier, UserContext user) {
    return sample().resolveAsset(new Asset(AssetType.TABLE, identifier), AuthContext.of(user));
  }

  @Test
  void vendsPlaceholderCredentialsScopedToTheAssetLocation() {
    List<StorageCredentials> vended =
        sample()
            .getStorageCredentials(
                new CredentialRequest(
                    AssetType.TABLE,
                    "main.sales.table1",
                    "main.sales.table1",
                    TABLE1,
                    StorageOperation.READ,
                    Duration.ofMinutes(5)),
                AuthContext.of(ALICE));

    assertEquals(1, vended.size(), "this connector scopes to the one location it was asked about");
    StorageCredentials credentials = vended.get(0);
    assertEquals(CloudProvider.AWS, credentials.provider());
    assertEquals(TABLE1, credentials.prefix());
    assertTrue(credentials.expiration().isAfter(java.time.Instant.now()));
    assertTrue(credentials.require(StorageCredentials.ACCESS_KEY_ID).startsWith("ASIA"));
    assertTrue(!credentials.require(StorageCredentials.SESSION_TOKEN).isBlank());
  }

  @Test
  void listsTheTablesOneLevelBelowASchema() {
    String yaml =
        """
        assets:
          - identifier: main.sales
            type: SCHEMA
          - identifier: main.sales.table1
            storageLocation: s3://delta-exchange-test/delta-exchange-test/table1/
            format: delta
          - identifier: main.sales.nested.extra
            storageLocation: s3://delta-exchange-test/delta-exchange-test/table1/
          - identifier: main.other.table1
            storageLocation: s3://delta-exchange-test/delta-exchange-test/table1/
        """;

    List<ResolvedAsset> children =
        connector(yaml)
            .listChildren(
                new Asset(AssetType.SCHEMA, "MAIN.SALES"),
                100,
                null,
                AuthContext.of(ALICE))
            .assets();

    assertEquals(
        List.of("main.sales.table1"),
        children.stream().map(ResolvedAsset::fullName).toList(),
        "a table two levels down belongs to another schema, and one elsewhere to none of it");
    assertEquals(TABLE1, children.get(0).location().storageLocation());
  }

  @Test
  void listChildrenOmitsTablesTheCallerMayNotShare() {
    Asset finance = new Asset(AssetType.SCHEMA, "main.finance");
    assertEquals(
        List.of("main.finance.ledger"),
        sample().listChildren(finance, 100, null, AuthContext.of(ALICE)).assets().stream()
            .map(ResolvedAsset::fullName)
            .toList());
    assertEquals(
        List.of(), sample().listChildren(finance, 100, null, AuthContext.of(BOB)).assets());
  }


  @Test
  void refusesToListWhatIsNotAContainer() {
    LocalCatalogConnector connector = sample();
    Asset table = new Asset(AssetType.TABLE, "main.sales.table1");

    assertThrows(
        UnsupportedAssetTypeException.class,
        () -> connector.listChildren(table, 100, null, AuthContext.of(ALICE)));
  }

  @Test
  void refusesToListASchemaItDoesNotHave() {
    LocalCatalogConnector connector = sample();
    Asset schema = new Asset(AssetType.SCHEMA, "main.missing");

    assertThrows(
        AssetNotFoundException.class,
        () -> connector.listChildren(schema, 100, null, AuthContext.of(ALICE)));
  }

  @Test
  void pagesThroughTheTablesBelowASchema() {
    String yaml =
        """
        assets:
          - identifier: main.sales
            type: SCHEMA
          - identifier: main.sales.c
            storageLocation: s3://delta-exchange-test/delta-exchange-test/table1/
          - identifier: main.sales.a
            storageLocation: s3://delta-exchange-test/delta-exchange-test/table1/
          - identifier: main.sales.b
            storageLocation: s3://delta-exchange-test/delta-exchange-test/table1/
        """;
    LocalCatalogConnector connector = connector(yaml);
    Asset schema = new Asset(AssetType.SCHEMA, "main.sales");

    AssetPage first = connector.listChildren(schema, 2, null, AuthContext.of(ALICE));
    assertEquals(
        List.of("main.sales.a", "main.sales.b"),
        first.assets().stream().map(ResolvedAsset::fullName).toList());

    AssetPage last =
        connector.listChildren(schema, 2, first.nextPageToken(), AuthContext.of(ALICE));
    assertEquals(
        List.of("main.sales.c"), last.assets().stream().map(ResolvedAsset::fullName).toList());
    assertNull(last.nextPageToken(), "the last page has no next page");
  }

  @Test
  void rejectsPageTokensItDidNotIssue() {
    LocalCatalogConnector connector = sample();
    Asset schema = new Asset(AssetType.SCHEMA, "main.sales");

    assertThrows(
        IllegalArgumentException.class,
        () -> connector.listChildren(schema, 2, "not-a-token", AuthContext.of(ALICE)));
    assertThrows(
        IllegalArgumentException.class,
        () -> connector.listChildren(schema, 0, null, AuthContext.of(ALICE)));
  }

  @Test
  void authorizesConfiguredLocalPrincipals() {
    LocalCatalogConnector connector = sample();

    UserContext alice =
        connector.authorize(
            new AuthContext(null, new UserContext(null, "alice-token", null)),
            Privilege.CREATE_SHARE);
    assertEquals("catalog-alice-id", alice.userId());
    assertEquals("alice", alice.userName());
    assertThrows(
        CatalogAuthenticationException.class,
        () ->
            connector.authorize(
                new AuthContext(null, new UserContext(null, "mallory-token", null)), null));
  }

  /** The sample catalog lists catalog-alice-id in adminUserIds. */
  @Test
  void marksConfiguredAdminsOnAuthorize() {
    LocalCatalogConnector connector = sample();

    assertTrue(connector.authorize(authByToken("alice-token"), null).admin());
    assertFalse(connector.authorize(authByToken("bob-token"), null).admin());
  }

  /**
   * OpenSharing names the user it acts for by id when it has no token, such as when it checks that
   * a share's new owner exists.
   */
  @Test
  void authorizesAUserByIdWithoutAToken() {
    LocalCatalogConnector connector = sample();

    UserContext bob =
        connector.authorize(
            new AuthContext("opensharing-server", UserContext.fromUserIdAndName("catalog-bob-id", null)),
            null);
    assertEquals("catalog-bob-id", bob.userId());
    assertFalse(bob.admin());

    assertThrows(
        CatalogAuthenticationException.class,
        () ->
            connector.authorize(
                new AuthContext(
                    "opensharing-server", UserContext.fromUserIdAndName("catalog-mallory-id", null)),
                null));
    assertThrows(
        CatalogAuthenticationException.class,
        () -> connector.authorize(new AuthContext("opensharing-server", UserContext.fromUserIdAndName(null, null)), null));
  }

  private static AuthContext authByToken(String token) {
    return new AuthContext(null, new UserContext(null, token, null));
  }


  @Test
  void rejectsUnknownKeysInCatalogFile() {
    String yaml =
        """
        assets:
          - identifier: main.sales.orders
            type: TABLE
            storage_locationn: s3://typo/
        """;

    assertThrows(CatalogException.class, () -> connector(yaml));
  }

  @Test
  void rejectsTheRemovedStaticCredentials() {
    String mode =
        """
        credentials:
          mode: STATIC
        """;
    String values =
        """
        credentials:
          values:
            accessKeyId: configured
        """;

    String modeError =
        assertThrows(CatalogException.class, () -> connector(mode)).getCause().getMessage();
    assertTrue(modeError.contains("\"STATIC\""), modeError);
    String valuesError =
        assertThrows(CatalogException.class, () -> connector(values)).getCause().getMessage();
    assertTrue(valuesError.contains("\"values\""), valuesError);
  }

  @Test
  void rejectsUnsupportedFormatAtLoad() {
    String yaml =
        """
        assets:
          - identifier: main.sales.orders
            storageLocation: s3://delta-exchange-test/delta-exchange-test/table1/
            format: orc
        """;

    assertThrows(CatalogException.class, () -> connector(yaml));
  }
}
