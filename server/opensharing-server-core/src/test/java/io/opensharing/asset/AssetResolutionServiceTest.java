package io.opensharing.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.opensharing.asset.storage.LocalFileUrlSigner;
import io.opensharing.asset.storage.S3UrlSigner;
import io.opensharing.asset.storage.UrlSigners;
import io.opensharing.catalog.AccessMode;
import io.opensharing.catalog.AssetAccessDeniedException;
import io.opensharing.catalog.AssetLookup;
import io.opensharing.catalog.AssetNotFoundException;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CatalogCaller;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.CatalogException;
import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.CredentialRequest;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentialKeys;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.catalog.StorageOperation;
import io.opensharing.catalog.TableFormat;
import io.opensharing.catalog.UnsupportedAssetTypeException;
import io.opensharing.config.OpenSharingProperties;
import io.opensharing.http.ApiException;
import io.opensharing.share.ShareEntity;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * What happens to a shared object when the catalog stops handing it over. The status is what keeps a
 * broken object out of recipient responses instead of failing each request forever.
 */
class AssetResolutionServiceTest {

  private static final String NAME = "main.sales.orders";
  private static final String OWNER = "alice-owner-id";
  private static final CatalogCaller OWNER_CALLER = CatalogCaller.onBehalfOf(OWNER, OWNER);

  @Test
  void recordsThatASourceHasGoneMissing() {
    SharedDataObjectEntity object = sharedObject();
    AssetResolutionService resolution =
        serviceThat(
            lookup -> {
              throw new AssetNotFoundException(lookup);
            });

    ApiException failure =
        assertThrows(ApiException.class, () -> resolution.resolveForServing(object));

    assertEquals(HttpStatus.NOT_FOUND, failure.getStatus());
    assertEquals(SharedObjectStatus.SOURCE_NOT_FOUND, object.getStatus());
  }

  @Test
  void recordsThatTheOwnerMayNoLongerReadTheSource() {
    SharedDataObjectEntity object = sharedObject();
    AssetResolutionService resolution =
        serviceThat(
            lookup -> {
              throw new AssetAccessDeniedException(lookup, OWNER_CALLER);
            });

    ApiException failure =
        assertThrows(ApiException.class, () -> resolution.resolveForServing(object));

    assertEquals(HttpStatus.FORBIDDEN, failure.getStatus());
    assertEquals(SharedObjectStatus.PERMISSION_DENIED, object.getStatus());
  }

  @Test
  void revivesAnObjectWhoseSourceComesBack() {
    SharedDataObjectEntity object = sharedObject();
    object.setStatus(SharedObjectStatus.SOURCE_NOT_FOUND);
    AssetResolutionService resolution =
        serviceThat(
            lookup ->
                ResolvedAsset.builder(AssetType.TABLE, lookup.identifier())
                    .storageLocation("s3://lake/sales/orders/")
                    .format(TableFormat.DELTA)
                    .build());

    resolution.resolveForServing(object);

    assertEquals(SharedObjectStatus.ACTIVE, object.getStatus());
    assertEquals("s3://lake/sales/orders/", object.getStorageLocation());
  }

  @Test
  void resolvesWhatARecipientReadsAsTheOwnerOfTheShare() {
    SharedDataObjectEntity object = sharedObject();
    AtomicReference<CatalogCaller> asked = new AtomicReference<>();
    AssetResolutionService resolution =
        service(
            lookup -> ResolvedAsset.builder(AssetType.TABLE, lookup.identifier()).build(),
            request -> List.of(),
            asked);

    resolution.resolveForServing(object);

    assertEquals(OWNER, asked.get().name(), "the recipient is nobody the catalog knows");
    assertEquals(
        OWNER,
        ((CatalogCaller.Credential.OnBehalfOf) asked.get().credential()).catalogUserId(),
        "there is no stored credential to present, only the owner's own catalog id");
  }

  private static SharedDataObjectEntity sharedObject() {
    ShareEntity share = new ShareEntity();
    share.setOwnerId(OWNER);
    SharedDataObjectEntity object = new SharedDataObjectEntity();
    object.setShare(share);
    object.setName(NAME);
    object.setType(AssetType.TABLE);
    object.setSharedAs("sales.orders");
    return object;
  }

  @Test
  void picksTheNarrowestVendedPrefixCoveringTheLocation() {
    AssetResolutionService resolution =
        serviceVending(
            List.of(
                credentialsFor("s3://lake/"),
                credentialsFor("s3://lake/sales/orders/"),
                credentialsFor("s3://other/")));

    assertEquals(
        "s3://lake/sales/orders/",
        resolution
            .vendCredentials(sharedObject(), requestFor("s3://lake/sales/orders/data/"))
            .prefix());
  }

  @Test
  void refusesCredentialsThatDoNotCoverTheLocation() {
    AssetResolutionService resolution = serviceVending(List.of(credentialsFor("s3://other/")));

    CatalogException failure =
        assertThrows(
            CatalogException.class,
            () ->
                resolution.vendCredentials(sharedObject(), requestFor("s3://lake/sales/orders/")));
    assertTrue(failure.getMessage().contains("none covering"));
  }

  @Test
  void refusesAnEmptyVendForATableInTheCloud() {
    AssetResolutionService resolution = serviceVending(List.of());

    assertThrows(
        CatalogException.class,
        () -> resolution.vendCredentials(sharedObject(), requestFor("s3://lake/sales/orders/")));
  }

  /**
   * Storage the server reaches on its own account has nothing to vend, and a catalog says so by
   * vending nothing: Unity Catalog answers a local table exactly this way, and reads it itself on
   * the same answer. Refusing that would refuse a table that is perfectly readable, so the absence
   * is passed on as an absence — from a local path only, which is what keeps the case above a
   * failure.
   */
  @Test
  void takesAnEmptyVendForALocalTableAsNothingBeingNeeded() {
    AssetResolutionService resolution = serviceVending(List.of());

    assertNull(resolution.vendCredentials(sharedObject(), requestFor("file:/srv/lake/orders/")));
    assertNull(resolution.vendCredentials(sharedObject(), requestFor("/srv/lake/orders/")));
  }

  /**
   * A catalog that authorizes minting separately can refuse it after having resolved the table
   * happily. What a recipient is told then must not name the owner the catalog was asked as, which
   * is no business of theirs, and the object must be recorded as unservable just as a refused
   * resolution records it.
   */
  @Test
  void tellsARecipientNothingAboutTheOwnerWhenTheCatalogWillNotMint() {
    SharedDataObjectEntity object = sharedObject();
    AssetResolutionService resolution =
        service(
            lookup -> ResolvedAsset.builder(AssetType.TABLE, lookup.identifier()).build(),
            request -> {
              throw new AssetAccessDeniedException(
                  AssetLookup.of(AssetType.TABLE, NAME), OWNER_CALLER);
            });

    ApiException failure =
        assertThrows(
            ApiException.class,
            () -> resolution.vendCredentials(object, requestFor("s3://lake/sales/orders/")));

    assertEquals(HttpStatus.FORBIDDEN, failure.getStatus());
    assertEquals(SharedObjectStatus.PERMISSION_DENIED, object.getStatus());
    assertFalse(failure.getMessage().contains(OWNER), "the owner is not the recipient's business");
    assertFalse(
        failure.getMessage().contains("alice-catalog-credential"),
        "and neither is what they were asked as");
  }

  /**
   * A table shareable when it was added can stop being one — recreated as CSV, or replaced by a view.
   * The request has not changed, so answering that it is invalid would blame the recipient for the
   * catalog's doing, on every read from now on. It is withdrawn instead, and what it became is left
   * to the log rather than told to whoever asked.
   */
  @Test
  void withdrawsATableThatStoppedBeingSomethingItCanShare() {
    SharedDataObjectEntity object = sharedObject();
    AssetResolutionService resolution =
        service(
            lookup -> {
              throw new UnsupportedAssetTypeException(
                  "'" + NAME + "' is CSV in the catalog, and this server shares Delta and Parquet");
            },
            request -> List.of());

    ApiException failure =
        assertThrows(ApiException.class, () -> resolution.resolveForServing(object));

    assertEquals(HttpStatus.NOT_FOUND, failure.getStatus());
    assertEquals(SharedObjectStatus.SOURCE_NOT_SHAREABLE, object.getStatus());
    assertFalse(failure.getMessage().contains("CSV"), "what it became is in the log, not the answer");
    assertFalse(failure.getMessage().contains(NAME), "nor the name it goes by in the catalog");
  }

  /**
   * Both ways of reading rest on something outside this server's gift, and a table can fall between
   * them: nothing is vended for a local path, and a parquet table has no log to replay and hand out
   * urls from. Sharing it would promise a recipient a table no route reaches, so the provider hears
   * it now, with both halves of the reason.
   */
  @Test
  void refusesToShareATableNoAccessModeCouldServe() {
    AssetResolutionService resolution = serviceVending(List.of());

    ApiException failure =
        assertThrows(
            ApiException.class,
            () ->
                resolution.requireServable(
                    ResolvedAsset.builder(AssetType.TABLE, NAME)
                        .storageLocation("/srv/lake/orders")
                        .format(TableFormat.PARQUET)
                        .build()));

    assertEquals(HttpStatus.BAD_REQUEST, failure.getStatus());
    assertTrue(failure.getMessage().contains("no credentials for /srv/lake/orders"));
    assertTrue(failure.getMessage().contains("serves Delta tables, not parquet ones"));
  }

  @Test
  void sharesATableEitherModeCanServe() {
    AssetResolutionService resolution = serviceVending(List.of());

    resolution.requireServable(
        ResolvedAsset.builder(AssetType.TABLE, NAME)
            .storageLocation("/srv/lake/orders")
            .format(TableFormat.DELTA)
            .build());
    resolution.requireServable(
        ResolvedAsset.builder(AssetType.TABLE, NAME)
            .storageLocation("s3://lake/sales/orders/")
            .format(TableFormat.PARQUET)
            .accessModes(Set.of(AccessMode.DIR))
            .build());
  }

  private static StorageCredentials credentialsFor(String prefix) {
    return new StorageCredentials(
        prefix, CloudProvider.AWS, Map.of(StorageCredentialKeys.ACCESS_KEY_ID, "AKIA"), null);
  }

  private static CredentialRequest requestFor(String location) {
    return new CredentialRequest(
        AssetType.TABLE, NAME, NAME, location, StorageOperation.READ, Duration.ofMinutes(5));
  }

  private static AssetResolutionService serviceThat(
      Function<AssetLookup, ResolvedAsset> resolveAsset) {
    return service(resolveAsset, request -> List.of());
  }

  private static AssetResolutionService serviceVending(List<StorageCredentials> minted) {
    return service(
        lookup -> ResolvedAsset.builder(AssetType.TABLE, lookup.identifier()).build(),
        request -> minted);
  }

  private static AssetResolutionService service(
      Function<AssetLookup, ResolvedAsset> resolveAsset,
      Function<CredentialRequest, List<StorageCredentials>> vend) {
    return service(resolveAsset, vend, new AtomicReference<>());
  }

  private static AssetResolutionService service(
      Function<AssetLookup, ResolvedAsset> resolveAsset,
      Function<CredentialRequest, List<StorageCredentials>> vend,
      AtomicReference<CatalogCaller> asked) {
    CatalogConnector catalog =
        new CatalogConnector() {
          @Override
          public String name() {
            return "stub";
          }

          @Override
          public ResolvedAsset resolveAsset(AssetLookup lookup, CatalogCaller caller) {
            asked.set(caller);
            return resolveAsset.apply(lookup);
          }

          @Override
          public List<StorageCredentials> getStorageCredentials(
              CredentialRequest request, CatalogCaller caller) {
            asked.set(caller);
            return vend.apply(request);
          }
        };
    return new AssetResolutionService(
        catalog,
        mock(SharedDataObjectStore.class),
        new OpenSharingProperties(),
        // The signers a real deployment has for the clouds these tests name locations on.
        new UrlSigners(List.of(new S3UrlSigner(new OpenSharingProperties()), new LocalFileUrlSigner())));
  }
}
