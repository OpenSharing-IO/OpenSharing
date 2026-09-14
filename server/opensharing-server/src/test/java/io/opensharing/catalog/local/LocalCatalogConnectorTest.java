package io.opensharing.catalog.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.catalog.AccessMode;
import io.opensharing.catalog.AssetAccessDeniedException;
import io.opensharing.catalog.AssetLookup;
import io.opensharing.catalog.AssetNotFoundException;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CatalogCaller;
import io.opensharing.catalog.CatalogException;
import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.CredentialRequest;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentialKeys;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.catalog.StorageOperation;
import io.opensharing.catalog.TableFormat;
import io.opensharing.catalog.UnsupportedAssetTypeException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LocalCatalogConnectorTest {

  private static final String CATALOG =
      """
      credentials:
        provider: AWS
        mode: FAKE
        ttlSeconds: 900
      assets:
        - identifier: main.sales.orders
          type: TABLE
          subtype: MANAGED
          storageLocation: s3://lake/sales/orders/
          format: delta
          auxiliaryLocations:
            - s3://lake-overflow/sales/orders/
        - identifier: main.research.notes
          storageLocation: s3://lake/research/notes/
          format: iceberg
        - identifier: main.finance.ledger
          storageLocation: s3://lake/finance/ledger/
          sharableBy:
            - alice@example.com
      """;

  private static final CatalogCaller ALICE =
      CatalogCaller.withBearerToken("alice@example.com", "secret");

  private static LocalCatalogConnector connector(String yaml) {
    return new LocalCatalogConnector(
        LocalCatalogLoader.load(
            new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test"));
  }

  @Test
  void resolvesTableWithFormatAndDirectoryAccess() {
    ResolvedAsset asset = resolve(CATALOG, "main.sales.orders", ALICE);

    assertEquals("s3://lake/sales/orders/", asset.storageLocation());
    assertEquals(TableFormat.DELTA, asset.format());
    assertEquals("MANAGED", asset.subtype());
    assertEquals(Set.of(AccessMode.DIR), asset.accessModes());
    assertEquals(List.of("s3://lake-overflow/sales/orders/"), asset.auxiliaryLocations());
  }

  @Test
  void treatsAssetsWithoutAnExplicitTypeAsTables() {
    ResolvedAsset asset = resolve(CATALOG, "main.research.notes", ALICE);

    assertEquals(AssetType.TABLE, asset.type());
    assertEquals(TableFormat.ICEBERG, asset.format());
  }

  @Test
  void resolvesNamesCaseInsensitively() {
    assertEquals(
        "s3://lake/sales/orders/",
        resolve(CATALOG, "MAIN.Sales.Orders", ALICE).storageLocation());
  }

  @Test
  void rejectsUnknownAsset() {
    LocalCatalogConnector connector = connector(CATALOG);
    AssetLookup lookup = AssetLookup.of(AssetType.TABLE, "main.sales.missing");

    assertThrows(
        AssetNotFoundException.class,
        () -> connector.resolveAsset(lookup, ALICE));
  }

  /**
   * Serving consults the same list, of the owner of the share being read through, so this is what
   * revokes an existing read as well as what refuses a new share. The credential each caller carries
   * is ignored: this file authenticates nobody, it only recognizes names.
   */
  @Test
  void letsOnlyTheListedPrincipalsShareARestrictedAsset() {
    assertEquals(
        "s3://lake/finance/ledger/",
        resolve(CATALOG, "main.finance.ledger", ALICE).storageLocation());

    LocalCatalogConnector connector = connector(CATALOG);
    AssetLookup lookup = AssetLookup.of(AssetType.TABLE, "main.finance.ledger");
    CatalogCaller bob = CatalogCaller.withBearerToken("bob@example.com", "bob-catalog-credential");
    assertThrows(AssetAccessDeniedException.class, () -> connector.resolveAsset(lookup, bob));
  }

  private static ResolvedAsset resolve(String yaml, String identifier, CatalogCaller caller) {
    return connector(yaml).resolveAsset(AssetLookup.of(AssetType.TABLE, identifier), caller);
  }

  @Test
  void resolvesTheMetadataPointerAndSchemaWhenTheCatalogStatesThem() {
    String yaml =
        """
        assets:
          - identifier: main.research.trials
            format: iceberg
            storageLocation: s3://lake/research/trials/
            metadataLocation: s3://lake/research/trials/metadata/v3.metadata.json
            schema: '{"type":"struct","fields":[]}'
        """;

    ResolvedAsset asset = resolve(yaml, "main.research.trials", ALICE);

    assertEquals(
        "s3://lake/research/trials/metadata/v3.metadata.json", asset.metadataLocation());
    assertEquals("{\"type\":\"struct\",\"fields\":[]}", asset.schema());
  }

  @Test
  void vendsPlaceholderCredentialsScopedToTheAssetLocation() {
    List<StorageCredentials> vended =
        connector(CATALOG)
            .getStorageCredentials(
                new CredentialRequest(
                    AssetType.TABLE,
                    "main.sales.orders",
                    "main.sales.orders",
                    "s3://lake/sales/orders/",
                    StorageOperation.READ,
                    Duration.ofMinutes(5)),
                ALICE);

    assertEquals(1, vended.size(), "this connector scopes to the one location it was asked about");
    StorageCredentials credentials = vended.get(0);
    assertEquals(CloudProvider.AWS, credentials.provider());
    assertEquals("s3://lake/sales/orders/", credentials.prefix());
    assertTrue(credentials.expiration().isAfter(java.time.Instant.now()));
    assertTrue(credentials.require(StorageCredentialKeys.ACCESS_KEY_ID).startsWith("ASIA"));
    assertTrue(!credentials.require(StorageCredentialKeys.SESSION_TOKEN).isBlank());
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
          - identifier: main.sales.orders
            type: TABLE
            storageLocation: abfss://lake@acme.dfs.core.windows.net/sales/orders/
        """;

    StorageCredentials credentials =
        connector(yaml)
            .getStorageCredentials(
                new CredentialRequest(
                    AssetType.TABLE,
                    "main.sales.orders",
                    null,
                    "abfss://lake@acme.dfs.core.windows.net/sales/orders/",
                    StorageOperation.READ,
                    null),
                ALICE)
            .get(0);

    assertEquals(CloudProvider.AZURE, credentials.provider());
    assertEquals(
        "sv=2024-11-04&sig=configured", credentials.require(StorageCredentialKeys.SAS_TOKEN));
  }

  @Test
  void rejectsStaticModeWithMissingValues() {
    String yaml =
        """
        credentials:
          provider: GCP
          mode: STATIC
        assets:
          - identifier: main.sales.orders
            type: TABLE
            storageLocation: gs://lake/sales/orders/
        """;
    LocalCatalogConnector connector = connector(yaml);
    CredentialRequest request =
        new CredentialRequest(
            AssetType.TABLE,
            "main.sales.orders",
            null,
            "gs://lake/sales/orders/",
            StorageOperation.READ,
            null);

    assertThrows(CatalogException.class, () -> connector.getStorageCredentials(request, ALICE));
  }

  @Test
  void listsTheTablesOneLevelBelowASchema() {
    String yaml =
        """
        assets:
          - identifier: main.hr
            type: SCHEMA
          - identifier: main.hr.employees
            storageLocation: s3://lake/hr/employees/
            format: delta
          - identifier: main.hr.contracts.clauses
            storageLocation: s3://lake/hr/contracts/clauses/
          - identifier: main.sales.orders
            storageLocation: s3://lake/sales/orders/
        """;

    List<ResolvedAsset> children =
        connector(yaml)
            .listChildren(AssetLookup.of(AssetType.SCHEMA, "MAIN.HR"), ALICE);

    assertEquals(
        List.of("main.hr.employees"),
        children.stream().map(ResolvedAsset::identifier).toList(),
        "a table two levels down belongs to another schema, and one elsewhere to none of it");
    assertEquals("s3://lake/hr/employees/", children.get(0).storageLocation());
  }

  @Test
  void refusesToListWhatIsNotAContainer() {
    LocalCatalogConnector connector = connector(CATALOG);
    AssetLookup table = AssetLookup.of(AssetType.TABLE, "main.sales.orders");

    assertThrows(
        UnsupportedAssetTypeException.class,
        () -> connector.listChildren(table, ALICE));
  }

  @Test
  void refusesToListASchemaItDoesNotHave() {
    LocalCatalogConnector connector = connector(CATALOG);
    AssetLookup schema = AssetLookup.of(AssetType.SCHEMA, "main.sales");

    assertThrows(
        AssetNotFoundException.class,
        () -> connector.listChildren(schema, ALICE));
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
            storageLocation: s3://lake/sales/orders/
            format: orc
        """;

    assertThrows(CatalogException.class, () -> connector(yaml));
  }

  @Test
  void rejectsUnsupportedAccessModeAtLoad() {
    String yaml =
        """
        assets:
          - identifier: main.sales.orders
            storageLocation: s3://lake/sales/orders/
            accessModes:
              - directory
        """;

    assertThrows(CatalogException.class, () -> connector(yaml));
  }
}
