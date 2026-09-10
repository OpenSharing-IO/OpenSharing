package io.opensharing.catalog.unity;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opensharing.catalog.AccessMode;
import io.opensharing.catalog.AssetAccessDeniedException;
import io.opensharing.catalog.AssetLookup;
import io.opensharing.catalog.AssetNotFoundException;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CatalogAuthorizationException;
import io.opensharing.catalog.CatalogCaller;
import io.opensharing.catalog.CatalogException;
import io.opensharing.catalog.CatalogPrincipal;
import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.CredentialRequest;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentialKeys;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.catalog.StorageOperation;
import io.opensharing.catalog.TableFormat;
import io.opensharing.catalog.UnsupportedAssetTypeException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The connector against a stub Unity Catalog: a real HTTP server answering canned JSON, so that the
 * request built, the header carrying the caller's credential, and the reading of the response are all
 * exercised the way a catalog would exercise them.
 */
class UnityCatalogConnectorTest {

  private static final String BASE_PATH = "/api/2.1/unity-catalog";
  private static final CatalogCaller ALICE =
      CatalogCaller.withBearerToken("alice@example.com", "alice-token");
  private static final String ORDERS = "main.sales.orders";

  private static final String DELTA_TABLE =
      """
      {
        "name": "orders",
        "catalog_name": "main",
        "schema_name": "sales",
        "table_type": "EXTERNAL",
        "data_source_format": "DELTA",
        "storage_location": "s3://lake/sales/orders/",
        "table_id": "c389adfa-5c8f-497b-8f70-26c2cca4976d",
        "owner": "bob@example.com",
        "columns": [
          {"name": "id", "type_text": "long", "position": 0},
          {"name": "month", "type_text": "string", "position": 2, "partition_index": 1},
          {"name": "year", "type_text": "string", "position": 1, "partition_index": 0}
        ]
      }
      """;

  private StubUnityCatalog catalog;
  private UnityCatalogConnector connector;

  @BeforeEach
  void start() throws IOException {
    catalog = new StubUnityCatalog();
    connector =
        new UnityCatalogConnector(catalog.uri(), Duration.ofSeconds(2), Duration.ofSeconds(10));
  }

  @AfterEach
  void stop() {
    catalog.close();
  }

  @Test
  void resolvesATableToWhereItLives() {
    catalog.answers("GET /tables/" + ORDERS, 200, DELTA_TABLE);

    ResolvedAsset asset = connector.resolveAsset(AssetLookup.of(AssetType.TABLE, ORDERS), ALICE);

    assertEquals("s3://lake/sales/orders/", asset.storageLocation());
    assertEquals(TableFormat.DELTA, asset.format());
    assertEquals("c389adfa-5c8f-497b-8f70-26c2cca4976d", asset.catalogAssetId());
    assertEquals("EXTERNAL", asset.subtype());
    assertEquals(
        List.of("year", "month"),
        asset.partitionColumns(),
        "partitioning is stated as an index per column, not as an ordered list");
  }

  @Test
  void asksTheCatalogAsThePrincipalTheRequestIsFor() {
    catalog.answers("GET /tables/" + ORDERS, 200, DELTA_TABLE);

    connector.resolveAsset(AssetLookup.of(AssetType.TABLE, ORDERS), ALICE);

    assertEquals("Bearer alice-token", catalog.lastRequest().authorization());
  }

  @Test
  void onBehalfOfPresentsTheConnectorsOwnServerSecretAndTheOwnersUserId() {
    UnityCatalogConnector onBehalfOfConnector =
        new UnityCatalogConnector(
            catalog.uri(), Duration.ofSeconds(2), Duration.ofSeconds(10), "shared-secret");
    catalog.answers("GET /tables/" + ORDERS, 200, DELTA_TABLE);
    CatalogCaller owner = CatalogCaller.onBehalfOf("alice@example.com", "owner-user-id");

    onBehalfOfConnector.resolveAsset(AssetLookup.of(AssetType.TABLE, ORDERS), owner);

    assertNull(
        catalog.lastRequest().authorization(),
        "on-behalf-of presents no bearer token at all, only the two headers below");
    assertEquals("shared-secret", catalog.lastRequest().serverSecret());
    assertEquals("owner-user-id", catalog.lastRequest().onBehalfOf());
  }

  @Test
  void onBehalfOfWithNoServerSecretConfiguredFailsRatherThanAskingUnauthenticated() {
    CatalogCaller owner = CatalogCaller.onBehalfOf("alice@example.com", "owner-user-id");

    CatalogException e =
        assertThrows(
            CatalogException.class,
            () -> connector.resolveAsset(AssetLookup.of(AssetType.TABLE, ORDERS), owner));

    assertTrue(e.getMessage().contains("server identity"));
  }

  @Test
  void authorizeResolvesTheTokensPrincipalWhenTheCatalogSaysYes() {
    catalog.answers(
        "GET /opensharing/authorize",
        200,
        """
        {"user_id": "alice-id", "user_name": "alice@example.com", "authorized": true}
        """);

    CatalogPrincipal resolved = connector.authorize("alice-token", "CREATE_SHARE");

    assertEquals("alice-id", resolved.id());
    assertEquals("alice@example.com", resolved.name());
    assertEquals("Bearer alice-token", catalog.lastRequest().authorization());
    assertEquals("privilege=CREATE_SHARE", catalog.lastRequest().query());
  }

  @Test
  void authorizeRefusesWhenTheCatalogSaysNoToThePrivilege() {
    catalog.answers(
        "GET /opensharing/authorize",
        200,
        """
        {"user_id": "mallory-id", "user_name": "mallory@example.com", "authorized": false}
        """);

    assertThrows(
        CatalogAuthorizationException.class,
        () -> connector.authorize("mallory-token", "CREATE_SHARE"));
  }

  @Test
  void authorizeRefusesWhenTheCatalogDoesNotRecognizeTheToken() {
    catalog.answers(
        "GET /opensharing/authorize",
        401,
        """
        {"error_code": "UNAUTHENTICATED", "message": "Invalid access token"}
        """);

    assertThrows(CatalogAuthorizationException.class, () -> connector.authorize("nonsense", null));
  }

  /**
   * The catalog's own blanket check on this endpoint turns away a bearer token from a principal it
   * has never heard of before this server's {authorized: false} body is ever produced — as 403, not
   * 401. Read the same as an unrecognized token, not as this connector failing.
   */
  @Test
  void authorizeRefusesWhenTheCatalogsOwnAuthorizationLayerRejectsOutright() {
    catalog.answers(
        "GET /opensharing/authorize",
        403,
        """
        {"error_code": "PERMISSION_DENIED", "message": "User not allowed: mallory@example.com"}
        """);

    assertThrows(
        CatalogAuthorizationException.class,
        () -> connector.authorize("mallory-token", "CREATE_SHARE"));
  }

  @Test
  void resolvesASchemaToNothingPhysical() {
    catalog.answers(
        "GET /schemas/main.sales",
        200,
        """
        {"name": "sales", "catalog_name": "main", "schema_id": "5a4b-schema"}
        """);

    ResolvedAsset asset =
        connector.resolveAsset(AssetLookup.of(AssetType.SCHEMA, "main.sales"), ALICE);

    assertEquals(AssetType.SCHEMA, asset.type());
    assertEquals("5a4b-schema", asset.catalogAssetId());
    assertNull(asset.storageLocation(), "a schema stands for its tables, and has no storage itself");
  }

  @Test
  void reportsATableTheCatalogDoesNotHave() {
    catalog.answers(
        "GET /tables/" + ORDERS,
        404,
        """
        {"error_code": "TABLE_DOES_NOT_EXIST", "message": "Table not found"}
        """);

    assertThrows(
        AssetNotFoundException.class,
        () -> connector.resolveAsset(AssetLookup.of(AssetType.TABLE, ORDERS), ALICE));
  }

  @Test
  void reportsATableTheCallerMayNotShare() {
    catalog.answers(
        "GET /tables/" + ORDERS,
        403,
        """
        {"error_code": "PERMISSION_DENIED", "message": "User does not have SELECT on table"}
        """);

    AssetAccessDeniedException refused =
        assertThrows(
            AssetAccessDeniedException.class,
            () -> connector.resolveAsset(AssetLookup.of(AssetType.TABLE, ORDERS), ALICE));
    assertTrue(refused.getMessage().contains("alice@example.com"));
  }

  /**
   * A rejected credential is the principal's, not this server's — it holds none — so it is reported
   * as them losing access rather than as the server failing to authenticate. That is what has the
   * object withdrawn instead of left listed and failing on every read.
   */
  @Test
  void readsARejectedCredentialAsAccessLost() {
    catalog.answers(
        "GET /tables/" + ORDERS,
        401,
        """
        {"error_code": "UNAUTHENTICATED", "message": "Invalid access token"}
        """);

    assertThrows(
        AssetAccessDeniedException.class,
        () -> connector.resolveAsset(AssetLookup.of(AssetType.TABLE, ORDERS), ALICE));
  }

  /**
   * A failure says the status and stops there. Whoever runs the server gets the request and the
   * catalog's own words from the log; a recipient reaching the same code must not be handed the
   * internal name of the table or text written upstream of here.
   */
  @Test
  void keepsTheCatalogsOwnComplaintOffTheWire() {
    catalog.answers(
        "GET /tables/" + ORDERS,
        500,
        """
        {"error_code": "INTERNAL_ERROR", "message": "metastore is down"}
        """);

    CatalogException failed =
        assertThrows(
            CatalogException.class,
            () -> connector.resolveAsset(AssetLookup.of(AssetType.TABLE, ORDERS), ALICE));
    assertTrue(failed.getMessage().contains("500"), failed.getMessage());
    assertFalse(failed.getMessage().contains("metastore is down"), failed.getMessage());
    assertFalse(failed.getMessage().contains(ORDERS), failed.getMessage());
  }

  /**
   * The vend is authorized separately from the resolve, so a table this catalog described happily can
   * still be refused a credential for. Read as the asset being denied, which is what withdraws it.
   */
  @Test
  void readsARefusedVendAsAccessLost() {
    catalog.answers("POST /temporary-table-credentials", 403, """
        {"error_code": "PERMISSION_DENIED", "message": "User does not have SELECT on table"}
        """);

    assertThrows(AssetAccessDeniedException.class, this::vend);
  }

  @Test
  void readsAVendForATableThatIsGoneAsMissing() {
    catalog.answers("POST /temporary-table-credentials", 404, """
        {"error_code": "TABLE_DOES_NOT_EXIST", "message": "Table does not exist"}
        """);

    assertThrows(AssetNotFoundException.class, this::vend);
  }

  @Test
  void refusesANameThatCouldNotBeAUnityCatalogTable() {
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () -> connector.resolveAsset(AssetLookup.of(AssetType.TABLE, "main.orders"), ALICE));

    assertTrue(refused.getMessage().contains("catalog.schema.table"), refused.getMessage());
    assertEquals(
        List.of(), catalog.requests(), "a name that cannot exist is not worth a round trip");
  }

  @Test
  void refusesATableInAFormatItCannotShare() {
    catalog.answers(
        "GET /tables/" + ORDERS,
        200,
        """
        {
          "name": "orders", "catalog_name": "main", "schema_name": "sales",
          "table_type": "EXTERNAL", "data_source_format": "CSV",
          "storage_location": "s3://lake/sales/orders/", "table_id": "csv-1"
        }
        """);

    UnsupportedAssetTypeException refused =
        assertThrows(
            UnsupportedAssetTypeException.class,
            () -> connector.resolveAsset(AssetLookup.of(AssetType.TABLE, ORDERS), ALICE));
    assertTrue(refused.getMessage().contains("CSV"), refused.getMessage());
  }

  @Test
  void refusesAViewWithNoStorageToPointAt() {
    catalog.answers(
        "GET /tables/main.sales.recent",
        200,
        """
        {
          "name": "recent", "catalog_name": "main", "schema_name": "sales",
          "table_type": "VIEW", "table_id": "view-1",
          "view_definition": "SELECT * FROM main.sales.orders"
        }
        """);

    UnsupportedAssetTypeException refused =
        assertThrows(
            UnsupportedAssetTypeException.class,
            () ->
                connector.resolveAsset(
                    AssetLookup.of(AssetType.TABLE, "main.sales.recent"), ALICE));
    assertTrue(refused.getMessage().contains("VIEW"), refused.getMessage());
  }

  @Test
  void listsTheTablesOfASchemaAcrossPages() {
    catalog.answers(
        "GET /tables",
        200,
        """
        {
          "tables": [
            {"name": "orders", "catalog_name": "main", "schema_name": "sales",
             "data_source_format": "DELTA", "storage_location": "s3://lake/sales/orders/",
             "table_id": "1"}
          ],
          "next_page_token": "page-2"
        }
        """);
    catalog.answers(
        "GET /tables",
        200,
        """
        {
          "tables": [
            {"name": "returns", "catalog_name": "main", "schema_name": "sales",
             "data_source_format": "PARQUET", "storage_location": "s3://lake/sales/returns/",
             "table_id": "2"}
          ]
        }
        """);

    List<ResolvedAsset> tables =
        connector.listChildren(AssetLookup.of(AssetType.SCHEMA, "main.sales"), ALICE);

    assertEquals(
        List.of("main.sales.orders", "main.sales.returns"),
        tables.stream().map(ResolvedAsset::identifier).toList());
    assertEquals(TableFormat.PARQUET, tables.get(1).format());
    assertEquals(
        "catalog_name=main&schema_name=sales&max_results=50",
        catalog.requests().get(0).query(),
        "a listing is scoped to the schema it was asked about");
    assertTrue(
        catalog.requests().get(1).query().endsWith("page_token=page-2"),
        "the second page is asked for with the token the first one gave");
  }

  @Test
  void leavesOutTablesOfASchemaThatCouldNotBeServed() {
    catalog.answers(
        "GET /tables",
        200,
        """
        {
          "tables": [
            {"name": "orders", "catalog_name": "main", "schema_name": "sales",
             "data_source_format": "DELTA", "storage_location": "s3://lake/sales/orders/",
             "table_id": "1"},
            {"name": "raw", "catalog_name": "main", "schema_name": "sales",
             "data_source_format": "CSV", "storage_location": "s3://lake/sales/raw/",
             "table_id": "2"},
            {"name": "recent", "catalog_name": "main", "schema_name": "sales",
             "table_type": "VIEW", "table_id": "3"}
          ]
        }
        """);

    List<ResolvedAsset> tables =
        connector.listChildren(AssetLookup.of(AssetType.SCHEMA, "main.sales"), ALICE);

    assertEquals(
        List.of("main.sales.orders"),
        tables.stream().map(ResolvedAsset::identifier).toList(),
        "one unshareable table does not take the rest of the schema down with it");
  }

  @Test
  void refusesToListAnythingButASchema() {
    assertThrows(
        UnsupportedAssetTypeException.class,
        () -> connector.listChildren(AssetLookup.of(AssetType.TABLE, ORDERS), ALICE));
  }

  @Test
  void vendsCredentialsForTheTableIdTheCatalogGaveOut() {
    catalog.answers(
        "POST /temporary-table-credentials",
        200,
        """
        {
          "aws_temp_credentials": {
            "access_key_id": "ASIAEXAMPLE",
            "secret_access_key": "secret",
            "session_token": "session"
          },
          "expiration_time": 1900000000000,
          "url": "s3://lake/sales/"
        }
        """);

    StorageCredentials vended =
        connector.getStorageCredentials(readOf(ORDERS, "table-1"), ALICE).get(0);

    assertEquals(CloudProvider.AWS, vended.provider());
    assertEquals("ASIAEXAMPLE", vended.require(StorageCredentialKeys.ACCESS_KEY_ID));
    assertEquals("session", vended.require(StorageCredentialKeys.SESSION_TOKEN));
    assertEquals(Instant.ofEpochMilli(1900000000000L), vended.expiration());
    assertEquals(
        "s3://lake/sales/",
        vended.prefix(),
        "the catalog's own prefix is kept when it covers the location asked about");
    assertTrue(catalog.lastRequest().body().contains("\"table_id\":\"table-1\""));
    assertTrue(catalog.lastRequest().body().contains("\"operation\":\"READ\""));
    assertEquals("Bearer alice-token", catalog.lastRequest().authorization());
  }

  @Test
  void vendsWhicheverCloudTheCatalogMintedFor() {
    catalog.answers(
        "POST /temporary-table-credentials",
        200,
        """
        {"azure_user_delegation_sas": {"sas_token": "sv=2024-11-04&sig=abc"}}
        """);
    catalog.answers(
        "POST /temporary-table-credentials",
        200,
        """
        {"gcp_oauth_token": {"oauth_token": "ya29.example"}}
        """);

    StorageCredentials azure =
        connector.getStorageCredentials(readOf(ORDERS, "table-1"), ALICE).get(0);
    StorageCredentials gcp =
        connector.getStorageCredentials(readOf(ORDERS, "table-1"), ALICE).get(0);

    assertEquals(CloudProvider.AZURE, azure.provider());
    assertEquals("sv=2024-11-04&sig=abc", azure.require(StorageCredentialKeys.SAS_TOKEN));
    assertEquals(CloudProvider.GCP, gcp.provider());
    assertEquals("ya29.example", gcp.require(StorageCredentialKeys.OAUTH_TOKEN));
    assertNull(azure.expiration(), "no expiry is invented for a catalog that states none");
  }

  @Test
  void scopesToTheLocationAskedAboutWhenTheCatalogNamesAnotherOne() {
    catalog.answers(
        "POST /temporary-table-credentials",
        200,
        """
        {
          "aws_temp_credentials": {"access_key_id": "k", "secret_access_key": "s",
                                   "session_token": "t"},
          "url": "s3://lake/sales/orders/"
        }
        """);

    CredentialRequest request =
        new CredentialRequest(
            AssetType.TABLE,
            ORDERS,
            "table-1",
            "s3://lake/sales/orders",
            StorageOperation.READ,
            Duration.ofHours(1));

    assertEquals(
        "s3://lake/sales/orders",
        connector.getStorageCredentials(request, ALICE).get(0).prefix(),
        "a normalized prefix that appears to cover nothing is not used as the scope");
  }

  @Test
  void refusesToVendWhenTheCatalogMintedNothing() {
    catalog.answers("POST /temporary-table-credentials", 200, "{\"expiration_time\": null}");

    CatalogException failed =
        assertThrows(
            CatalogException.class,
            () -> connector.getStorageCredentials(readOf(ORDERS, "table-1"), ALICE));
    assertTrue(failed.getMessage().contains("vended no credentials"), failed.getMessage());
    assertFalse(
        failed.getMessage().contains("s3://lake/sales/orders/"),
        "which bucket the table is on is in the log, not in what a recipient reads");
  }

  /**
   * A recipient picks a mode from what a table offers, so dir mode is offered only for a table Unity
   * Catalog will actually mint for. A local table has no grant behind it and is served by url.
   */
  @Test
  void offersDirectoryAccessOnlyWhereThereIsAGrantToGetForIt() {
    catalog.answers("GET /tables/" + ORDERS, 200, DELTA_TABLE);
    catalog.answers(
        "GET /tables/main.sales.on_disk",
        200,
        """
        {
          "name": "on_disk",
          "full_name": "main.sales.on_disk",
          "table_type": "EXTERNAL",
          "data_source_format": "DELTA",
          "storage_location": "file:///srv/lake/on_disk",
          "table_id": "1ae43989-1194-4c4b-b7e8-6e111bf01fae"
        }
        """);

    assertEquals(
        Set.of(AccessMode.DIR),
        connector.resolveAsset(AssetLookup.of(AssetType.TABLE, ORDERS), ALICE).accessModes(),
        "a table on a bucket is one the catalog mints for");
    assertEquals(
        Set.of(),
        connector
            .resolveAsset(AssetLookup.of(AssetType.TABLE, "main.sales.on_disk"), ALICE)
            .accessModes());
  }

  /**
   * Unity Catalog mints in more shapes than this build reads. Offering dir mode for one of the others
   * would take the table into a share and then fail every vend, so the mode is not offered and the
   * table stands or falls by url access like any other.
   */
  @Test
  void offersNoDirectoryAccessForStorageItCouldNotReadTheGrantFor() {
    catalog.answers(
        "GET /tables/main.sales.on_r2",
        200,
        """
        {
          "name": "on_r2",
          "full_name": "main.sales.on_r2",
          "table_type": "EXTERNAL",
          "data_source_format": "DELTA",
          "storage_location": "r2://lake/sales/on_r2",
          "table_id": "3c1de0b2-8a54-4b6f-9d34-77a2f0c1e5aa"
        }
        """);

    assertEquals(
        Set.of(),
        connector
            .resolveAsset(AssetLookup.of(AssetType.TABLE, "main.sales.on_r2"), ALICE)
            .accessModes());
  }

  /**
   * The same empty answer about a table on disk is Unity Catalog saying there is nothing to hand
   * out, which is how it treats a {@code file:} location itself: it hands its own reader no
   * credentials and opens the file. Passed on as an empty vend, so the layers above read the table
   * the same way rather than refusing one that works.
   */
  @Test
  void vendsNothingForALocalTableBecauseNothingIsNeeded() {
    catalog.answers("POST /temporary-table-credentials", 200, "{}");
    CredentialRequest request =
        new CredentialRequest(
            AssetType.TABLE,
            ORDERS,
            "table-1",
            "file:/srv/lake/sales/orders",
            StorageOperation.READ,
            Duration.ofHours(1));

    assertEquals(List.of(), connector.getStorageCredentials(request, ALICE));
  }

  @Test
  void refusesToVendWithoutTheTableIdTheCatalogMintsBy() {
    CredentialRequest request = readOf(ORDERS, null);

    assertThrows(CatalogException.class, () -> connector.getStorageCredentials(request, ALICE));
    assertEquals(List.of(), catalog.requests(), "there is nothing to ask the catalog for");
  }

  /**
   * A name is one path segment however it is spelled. Without escaping, a table whose name holds a
   * slash would address a different endpoint altogether — the request would leave the collection it
   * was meant for — and a space would not make a valid request line at all.
   */
  @Test
  void keepsAnAwkwardNameInsideOnePathSegment() {
    catalog.answers("GET /tables/main.sales.a%20b%2Fc", 200, DELTA_TABLE);

    connector.resolveAsset(AssetLookup.of(AssetType.TABLE, "main.sales.a b/c"), ALICE);

    assertEquals("/tables/main.sales.a%20b%2Fc", catalog.lastRequest().path());
  }

  @Test
  void readsASchemaThatIsGoneAsMissing() {
    catalog.answers("GET /tables", 404, """
        {"error_code": "SCHEMA_DOES_NOT_EXIST", "message": "Schema does not exist"}
        """);

    assertThrows(
        AssetNotFoundException.class,
        () -> connector.listChildren(AssetLookup.of(AssetType.SCHEMA, "main.sales"), ALICE));
  }

  /**
   * A catalog that keeps handing out the same page token would otherwise be followed forever. Taken
   * as the end of the listing, since a token that does not advance describes no further page.
   */
  @Test
  void stopsWhenTheCatalogRepeatsAPageToken() {
    catalog.answers(
        "GET /tables",
        200,
        """
        {"tables": [%s], "next_page_token": "stuck"}
        """
            .formatted(DELTA_TABLE));

    assertEquals(
        1,
        connector.listChildren(AssetLookup.of(AssetType.SCHEMA, "main.sales"), ALICE).size(),
        "the page that repeats itself is counted once");
  }

  /**
   * And one that keeps advancing is followed only so far. A schema of that size is not one to
   * assemble per listing, so the provider is told to share its tables instead.
   */
  @Test
  void refusesASchemaWithMorePagesThanItWillFollow() {
    for (int page = 0; page < 201; page++) {
      catalog.answers(
          "GET /tables",
          200,
          """
          {"tables": [], "next_page_token": "page-%d"}
          """
              .formatted(page));
    }

    CatalogException refused =
        assertThrows(
            CatalogException.class,
            () -> connector.listChildren(AssetLookup.of(AssetType.SCHEMA, "main.sales"), ALICE));

    assertTrue(refused.getMessage().contains("share its tables individually"), refused.getMessage());
  }

  /**
   * A credential block with nothing in it is the catalog answering wrongly, and is refused as that.
   * Passed on, it would become a credential holding no values and fail while signing a url, which
   * says nothing about where the trouble came from.
   */
  @Test
  void refusesACredentialWithNothingInIt() {
    catalog.answers("POST /temporary-table-credentials", 200, """
        {"aws_temp_credentials": {}, "url": "s3://lake/sales/"}
        """);

    CatalogException refused = assertThrows(CatalogException.class, this::vend);

    assertTrue(refused.getMessage().contains("nothing in it"), refused.getMessage());
    assertFalse(refused.getMessage().contains(ORDERS), "the table it was for is in the log");
  }

  private List<StorageCredentials> vend() {
    return connector.getStorageCredentials(readOf(ORDERS, "table-1"), ALICE);
  }

  private static CredentialRequest readOf(String identifier, String tableId) {
    return new CredentialRequest(
        AssetType.TABLE,
        identifier,
        tableId,
        "s3://lake/sales/orders/",
        StorageOperation.READ,
        Duration.ofHours(1));
  }

  /**
   * A Unity Catalog that answers what a test told it to, and remembers what it was asked. Responses
   * are queued per request, so a test can hand out one page and then the next; the last one queued
   * keeps answering, which is what a test making the same call twice wants.
   */
  private static final class StubUnityCatalog implements AutoCloseable {

    private final HttpServer http;
    private final Map<String, Deque<String[]>> answers = new HashMap<>();
    private final List<Recorded> requests = new ArrayList<>();

    StubUnityCatalog() throws IOException {
      http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      http.createContext(BASE_PATH, this::handle);
      http.start();
    }

    URI uri() {
      return URI.create("http://127.0.0.1:" + http.getAddress().getPort() + BASE_PATH);
    }

    void answers(String request, int status, String body) {
      answers
          .computeIfAbsent(request, key -> new ArrayDeque<>())
          .add(new String[] {String.valueOf(status), body});
    }

    List<Recorded> requests() {
      return List.copyOf(requests);
    }

    Recorded lastRequest() {
      return requests.get(requests.size() - 1);
    }

    private void handle(HttpExchange exchange) throws IOException {
      // The raw path, so that a test can tell a name with a slash escaped into one segment from one
      // that broke out into two: decoding turns both into the same string.
      String path = exchange.getRequestURI().getRawPath().substring(BASE_PATH.length());
      String body = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
      requests.add(
          new Recorded(
              path,
              exchange.getRequestURI().getQuery(),
              exchange.getRequestHeaders().getFirst("Authorization"),
              body,
              exchange.getRequestHeaders().getFirst("X-OpenSharing-Server-Secret"),
              exchange.getRequestHeaders().getFirst("X-OpenSharing-On-Behalf-Of")));
      Deque<String[]> queued = answers.get(exchange.getRequestMethod() + " " + path);
      String[] answer =
          queued == null || queued.isEmpty()
              ? new String[] {"404", "{\"message\": \"no stub for " + path + "\"}"}
              : (queued.size() == 1 ? queued.peek() : queued.poll());
      byte[] bytes = answer[1].getBytes(UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(Integer.parseInt(answer[0]), bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }

    @Override
    public void close() {
      http.stop(0);
    }
  }

  private record Recorded(
      String path,
      String query,
      String authorization,
      String body,
      String serverSecret,
      String onBehalfOf) {}
}
