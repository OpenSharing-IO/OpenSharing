package io.opensharing.catalog.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.auth.AuthContext;
import io.opensharing.auth.UserContext;
import io.opensharing.exception.AssetAccessDeniedException;
import io.opensharing.catalog.AssetLookup;
import io.opensharing.exception.AssetNotFoundException;
import io.opensharing.catalog.AssetType;
import io.opensharing.exception.CatalogAuthorizationException;
import io.opensharing.exception.CatalogException;
import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.CredentialRequest;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.catalog.StorageOperation;
import io.opensharing.catalog.TableFormat;
import io.opensharing.exception.UnsupportedAssetTypeException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class LocalCatalogConnectorTest {

  private static final String TABLE1 =
      "s3://delta-exchange-test/delta-exchange-test/table1/";

  private static final String CATALOG =
      """
      credentials:
        provider: AWS
        mode: FAKE
        ttlSeconds: 900
      assets:
        - identifier: main.sales
          type: SCHEMA
        - identifier: main.sales.table1
          type: TABLE
          subtype: MANAGED
          storageLocation: s3://delta-exchange-test/delta-exchange-test/table1/
          format: delta
          tableVersion: 123
          tableVersionHistory:
            - version: 0
              timestamp: "2022-01-01T00:00:00Z"
            - version: 45
              timestamp: "2022-02-01T00:00:00Z"
            - version: 123
              timestamp: "2022-03-01T00:00:00Z"
        - identifier: main.finance.ledger
          storageLocation: s3://delta-exchange-test/delta-exchange-test/table1/
          format: delta
          sharableBy:
            - alice@example.com
      """;

  private static final UserContext ALICE =
      new UserContext("alice@example.com", "alice@example.com");

  private static LocalCatalogConnector connector(String yaml) {
    return new LocalCatalogConnector(
        LocalCatalogLoader.load(
            new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test"));
  }

  @Test
  void resolvesTableWithFormat() {
    ResolvedAsset asset = resolve(CATALOG, "main.sales.table1", ALICE);

    assertEquals(TABLE1, asset.storageLocation());
    assertEquals(TableFormat.DELTA, asset.format());
    assertEquals("MANAGED", asset.subtype());
  }

  @Test
  void resolvesCurrentTableVersion() {
    LocalCatalogConnector connector = connector(CATALOG);
    assertEquals(
        123,
        connector.getTableVersion(
            AssetLookup.of(AssetType.TABLE, "main.sales.table1"), null, AuthContext.of(ALICE)));
  }

  @Test
  void resolvesEarliestTableVersionAtOrAfterTimestamp() {
    LocalCatalogConnector connector = connector(CATALOG);
    AssetLookup table = AssetLookup.of(AssetType.TABLE, "main.sales.table1");

    assertEquals(
        0,
        connector.getTableVersion(
            table, java.time.Instant.parse("2021-12-01T00:00:00Z"), AuthContext.of(ALICE)));
    assertEquals(
        45,
        connector.getTableVersion(
            table, java.time.Instant.parse("2022-01-15T00:00:00Z"), AuthContext.of(ALICE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            connector.getTableVersion(
                table, java.time.Instant.parse("2022-04-01T00:00:00Z"), AuthContext.of(ALICE)));
  }

  @Test
  void treatsAssetsWithoutAnExplicitTypeAsTables() {
    ResolvedAsset asset = resolve(CATALOG, "main.finance.ledger", ALICE);

    assertEquals(AssetType.TABLE, asset.type());
    assertEquals(TableFormat.DELTA, asset.format());
  }

  @Test
  void resolvesNamesCaseInsensitively() {
    assertEquals(TABLE1, resolve(CATALOG, "MAIN.Sales.Table1", ALICE).storageLocation());
  }

  @Test
  void rejectsUnknownAsset() {
    LocalCatalogConnector connector = connector(CATALOG);
    AssetLookup lookup = AssetLookup.of(AssetType.TABLE, "main.sales.missing");

    assertThrows(
        AssetNotFoundException.class,
        () -> connector.resolveAsset(lookup, AuthContext.of(ALICE)));
  }

  /**
   * Serving consults the same list, of the owner of the share being read through, so this is what
   * revokes an existing read as well as what refuses a new share. This file authenticates nobody;
   * it only recognizes names.
   */
  @Test
  void letsOnlyTheListedPrincipalsShareARestrictedAsset() {
    assertEquals(TABLE1, resolve(CATALOG, "main.finance.ledger", ALICE).storageLocation());

    LocalCatalogConnector connector = connector(CATALOG);
    AssetLookup lookup = AssetLookup.of(AssetType.TABLE, "main.finance.ledger");
    UserContext bob = new UserContext("bob@example.com", "bob@example.com");
    assertThrows(AssetAccessDeniedException.class, () -> connector.resolveAsset(lookup, AuthContext.of(bob)));
  }

  @Test
  void refusesToVendWhenCallerIsNotOnSharableBy() {
    LocalCatalogConnector connector = connector(CATALOG);
    UserContext bob = new UserContext("bob@example.com", "bob@example.com");
    CredentialRequest request =
        new CredentialRequest(
            AssetType.TABLE,
            "main.finance.ledger",
            null,
            TABLE1,
            StorageOperation.READ,
            null);

    assertThrows(
        AssetAccessDeniedException.class, () -> connector.getStorageCredentials(request, AuthContext.of(bob)));
  }

  @Test
  void refusesToVendUnknownAsset() {
    LocalCatalogConnector connector = connector(CATALOG);
    CredentialRequest request =
        new CredentialRequest(
            AssetType.TABLE,
            "main.sales.missing",
            null,
            TABLE1,
            StorageOperation.READ,
            null);

    assertThrows(AssetNotFoundException.class, () -> connector.getStorageCredentials(request, AuthContext.of(ALICE)));
  }

  @Test
  void refusesToVendALocationOutsideTheAsset() {
    LocalCatalogConnector connector = connector(CATALOG);
    CredentialRequest request =
        new CredentialRequest(
            AssetType.TABLE,
            "main.sales.table1",
            null,
            "s3://someone-elses-bucket/secret/",
            StorageOperation.READ,
            null);

    assertThrows(CatalogException.class, () -> connector.getStorageCredentials(request, AuthContext.of(ALICE)));
  }

  private static ResolvedAsset resolve(String yaml, String identifier, UserContext user) {
    return connector(yaml).resolveAsset(AssetLookup.of(AssetType.TABLE, identifier), AuthContext.of(user));
  }

  @Test
  void resolvesSchemaWhenTheCatalogStatesIt() {
    String yaml =
        """
        assets:
          - identifier: main.research.trials
            format: delta
            storageLocation: s3://delta-exchange-test/delta-exchange-test/table1/
            schema: '{"type":"struct","fields":[]}'
        """;

    ResolvedAsset asset = resolve(yaml, "main.research.trials", ALICE);

    assertEquals("{\"type\":\"struct\",\"fields\":[]}", asset.schema());
  }

  @Test
  void vendsPlaceholderCredentialsScopedToTheAssetLocation() {
    List<StorageCredentials> vended =
        connector(CATALOG)
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
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void vendsConfiguredAwsKeysForTheExchangeTestTable() {
    String accessKey = System.getenv("AWS_ACCESS_KEY_ID");
    String secret = System.getenv("AWS_SECRET_ACCESS_KEY");
    String region = System.getenv().getOrDefault("AWS_REGION", "us-west-2");
    LocalCatalogFile file =
        new LocalCatalogFile(
            new LocalCatalogFile.Credentials(
                CloudProvider.AWS,
                LocalCatalogFile.CredentialMode.STATIC,
                900,
                Map.of(
                    StorageCredentials.ACCESS_KEY_ID, accessKey,
                    StorageCredentials.SECRET_ACCESS_KEY, secret,
                    StorageCredentials.REGION, region)),
            List.of(
                new LocalCatalogFile.Asset(
                    "main.sales.table1",
                    AssetType.TABLE,
                    "MANAGED",
                    TABLE1,
                    null,
                    "delta",
                    null,
                    List.of(),
                    null,
                    null,
                    List.of(),
                    List.of(),
                    List.of())));

    StorageCredentials credentials =
        new LocalCatalogConnector(file)
            .getStorageCredentials(
                new CredentialRequest(
                    AssetType.TABLE,
                    "main.sales.table1",
                    null,
                    TABLE1,
                    StorageOperation.READ,
                    Duration.ofMinutes(5)),
                AuthContext.of(ALICE))
            .get(0);

    assertEquals(TABLE1, credentials.prefix());
    assertEquals(CloudProvider.AWS, credentials.provider());
    assertEquals(accessKey, credentials.require(StorageCredentials.ACCESS_KEY_ID));
    assertEquals(region, credentials.credentials().get(StorageCredentials.REGION));
  }

  @Test
  void vendsConfiguredStaticCredentials() {
    String yaml =
        """
        credentials:
          provider: AZURE
          mode: STATIC
          values:
            sasToken: sv=2024-11-04&sig=configured
        assets:
          - identifier: main.sales.table1
            type: TABLE
            storageLocation: s3://delta-exchange-test/delta-exchange-test/table1/
        """;

    StorageCredentials credentials =
        connector(yaml)
            .getStorageCredentials(
                new CredentialRequest(
                    AssetType.TABLE,
                    "main.sales.table1",
                    null,
                    TABLE1,
                    StorageOperation.READ,
                    null),
                AuthContext.of(ALICE))
            .get(0);

    assertEquals(CloudProvider.AZURE, credentials.provider());
    assertEquals(
        "sv=2024-11-04&sig=configured", credentials.require(StorageCredentials.SAS_TOKEN));
  }

  @Test
  void rejectsStaticModeWithMissingValues() {
    String yaml =
        """
        credentials:
          provider: GCP
          mode: STATIC
        assets:
          - identifier: main.sales.table1
            type: TABLE
            storageLocation: s3://delta-exchange-test/delta-exchange-test/table1/
        """;
    LocalCatalogConnector connector = connector(yaml);
    CredentialRequest request =
        new CredentialRequest(
            AssetType.TABLE,
            "main.sales.table1",
            null,
            TABLE1,
            StorageOperation.READ,
            null);

    assertThrows(CatalogException.class, () -> connector.getStorageCredentials(request, AuthContext.of(ALICE)));
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
            .listChildren(AssetLookup.of(AssetType.SCHEMA, "MAIN.SALES"), AuthContext.of(ALICE));

    assertEquals(
        List.of("main.sales.table1"),
        children.stream().map(ResolvedAsset::identifier).toList(),
        "a table two levels down belongs to another schema, and one elsewhere to none of it");
    assertEquals(TABLE1, children.get(0).storageLocation());
  }

  @Test
  void listChildrenOmitsTablesTheCallerMayNotShare() {
    String yaml =
        """
        assets:
          - identifier: main.sales
            type: SCHEMA
          - identifier: main.sales.table1
            storageLocation: s3://delta-exchange-test/delta-exchange-test/table1/
            format: delta
          - identifier: main.sales.ledger
            storageLocation: s3://delta-exchange-test/delta-exchange-test/table1/
            format: delta
            sharableBy:
              - alice@example.com
        """;
    UserContext bob = new UserContext("bob@example.com", "bob@example.com");

    assertEquals(
        List.of("main.sales.ledger", "main.sales.table1"),
        connector(yaml).listChildren(AssetLookup.of(AssetType.SCHEMA, "main.sales"), AuthContext.of(ALICE)).stream()
            .map(ResolvedAsset::identifier)
            .toList());
    assertEquals(
        List.of("main.sales.table1"),
        connector(yaml).listChildren(AssetLookup.of(AssetType.SCHEMA, "main.sales"), AuthContext.of(bob)).stream()
            .map(ResolvedAsset::identifier)
            .toList());
  }

  @Test
  void refusesToListWhatIsNotAContainer() {
    LocalCatalogConnector connector = connector(CATALOG);
    AssetLookup table = AssetLookup.of(AssetType.TABLE, "main.sales.table1");

    assertThrows(
        UnsupportedAssetTypeException.class,
        () -> connector.listChildren(table, AuthContext.of(ALICE)));
  }

  @Test
  void refusesToListASchemaItDoesNotHave() {
    LocalCatalogConnector connector = connector(CATALOG);
    AssetLookup schema = AssetLookup.of(AssetType.SCHEMA, "main.missing");

    assertThrows(
        AssetNotFoundException.class,
        () -> connector.listChildren(schema, AuthContext.of(ALICE)));
  }

  @Test
  void authorizesConfiguredLocalPrincipals() {
    String yaml =
        """
        principals:
          - bearerToken: alice-token
            userId: catalog-alice-id
            userName: alice
        assets:
          - identifier: main.sales.table1
            storageLocation: s3://delta-exchange-test/delta-exchange-test/table1/
        """;
    LocalCatalogConnector connector = connector(yaml);

    UserContext alice =
        connector.authorize(
            new AuthContext(null, new UserContext(null, "alice-token", null)), "CREATE_SHARE");
    assertEquals("catalog-alice-id", alice.userId());
    assertEquals("alice", alice.userName());
    assertThrows(
        CatalogAuthorizationException.class,
        () ->
            connector.authorize(
                new AuthContext(null, new UserContext(null, "bob-token", null)), null));
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
