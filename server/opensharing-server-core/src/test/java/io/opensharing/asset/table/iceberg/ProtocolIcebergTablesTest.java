package io.opensharing.asset.table.iceberg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.asset.SharedDataObjectStore;
import io.opensharing.asset.share.ProtocolShares;
import io.opensharing.asset.table.ProtocolTables;
import io.opensharing.catalog.AssetLocation;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.DataSourceFormat;
import io.opensharing.catalog.ResolveOptions;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.catalog.TableProperties;
import io.opensharing.exception.CatalogException;
import io.opensharing.recipient.RecipientPrincipal;
import io.opensharing.share.ShareEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProtocolIcebergTablesTest {

  private static final ResolvedAsset TABLE =
      ResolvedAsset.builder(AssetType.TABLE, "main.lake.trips").build();

  @Test
  void loadCredentialsKeepsAllCredentialsInCoverageOrder() {
    // The share records the table location used to choose among overlapping credentials.
    SharedDataObjectEntity shared = new SharedDataObjectEntity();
    shared.setType(AssetType.TABLE);
    shared.setSourceFormat(DataSourceFormat.ICEBERG);
    shared.setStorageLocation("s3://bucket/t/");
    // Substitute only the authorization and storage dependencies, not the selection under test.
    ProtocolShares shares =
        new ProtocolShares(null, null) {
          @Override
          public ShareEntity requireGranted(RecipientPrincipal recipient, String shareName) {
            return new ShareEntity();
          }
        };
    SharedDataObjectStore objects =
        new SharedDataObjectStore(null) {
          @Override
          public Optional<SharedDataObjectEntity> findByAlias(
              ShareEntity share, String schema, String table) {
            return Optional.of(shared);
          }
        };
    ProtocolTables tables =
        new ProtocolTables(null, null, null, null, null, null, null) {
          @Override
          public List<StorageCredentials> readCredentialsForSharedTable(
              ShareEntity share, SharedDataObjectEntity table) {
            return List.of(
                credential("s3://bucket/", CloudProvider.AWS),
                credential("s3://bucket/t/", CloudProvider.AWS),
                new StorageCredentials(
                    "gs://other/", CloudProvider.GCP,
                    Map.of(StorageCredentials.OAUTH_TOKEN, "token"),
                    Instant.parse("2026-01-01T00:00:00Z")),
                new StorageCredentials(
                    "gs://another/", CloudProvider.GCP,
                    Map.of(StorageCredentials.OAUTH_TOKEN, "token"),
                    Instant.parse("2026-01-01T00:00:00Z")));
          }
        };
    // Exercise the public credentials lookup with multiple catalog results.
    List<IcebergStorageCredential> result =
        new ProtocolIcebergTables(shares, objects, tables)
            .loadCredentials(null, "share", "lake", "trips");
    // The most specific credential comes first; all others keep catalog order.
    assertEquals(
        List.of("s3://bucket/t/", "s3://bucket/", "gs://other/", "gs://another/"),
        result.stream().map(IcebergStorageCredential::prefix).toList());
  }

  @Test
  void refreshKeepsCatalogOrderWhenRecordedLocationIsStaleOrBlank() {
    // The catalog can have moved a table since it was shared.
    List<StorageCredentials> credentials =
        List.of(
            credential("s3://bucket/", CloudProvider.AWS),
            credential("s3://bucket/t/", CloudProvider.AWS));
    // A stale or absent location leaves catalog order unchanged.
    for (String root : new String[] {"s3://moved/", null, " "}) {
      // All returned entries retain their original order.
      assertEquals(
          List.of("s3://bucket/", "s3://bucket/t/"),
          refreshCredentials(credentials, root).stream()
              .map(IcebergStorageCredential::prefix)
              .toList());
    }
    // An empty catalog result is not a successful refresh.
    assertThrows(CatalogException.class, () -> refreshCredentials(List.of(), null));
  }

  @Test
  void refreshComparesNormalizedR2Locations() {
    // The catalog may vend credentials in either R2 or Iceberg S3 path form.
    List<StorageCredentials> credentials =
        List.of(
            credential("r2://bucket@acct.r2.cloudflarestorage.com/", CloudProvider.R2),
            credential("s3://bucket/t/", CloudProvider.AWS));
    // The normalized table-specific prefix wins without discarding the broader credential.
    assertEquals(
        List.of("s3://bucket/t/", "s3://bucket/"),
        refreshCredentials(credentials, "r2://bucket@acct.r2.cloudflarestorage.com/t/").stream()
            .map(IcebergStorageCredential::prefix)
            .toList());
  }

  private static List<IcebergStorageCredential> refreshCredentials(
      List<StorageCredentials> credentials, String root) {
    SharedDataObjectEntity shared = new SharedDataObjectEntity();
    shared.setType(AssetType.TABLE);
    shared.setSourceFormat(DataSourceFormat.ICEBERG);
    shared.setStorageLocation(root);
    // Isolate credential ordering from authorization, persistence, and catalog I/O.
    ProtocolShares shares =
        new ProtocolShares(null, null) {
          @Override
          public ShareEntity requireGranted(RecipientPrincipal recipient, String shareName) {
            return new ShareEntity();
          }
        };
    SharedDataObjectStore objects =
        new SharedDataObjectStore(null) {
          @Override
          public Optional<SharedDataObjectEntity> findByAlias(
              ShareEntity share, String schema, String table) {
            return Optional.of(shared);
          }
        };
    ProtocolTables tables =
        new ProtocolTables(null, null, null, null, null, null, null) {
          @Override
          public List<StorageCredentials> readCredentialsForSharedTable(
              ShareEntity share, SharedDataObjectEntity table) {
            return credentials;
          }
        };
    return new ProtocolIcebergTables(shares, objects, tables)
        .loadCredentials(null, "share", "lake", "trips");
  }

  @Test
  void rejectsInvalidCredentialPrefixesBeforeComparingCoverage() {
    // Invalid prefixes must not escape as unchecked path errors.
    for (String prefix : new String[] {null, " ", "r2://bucket/table/"}) {
      // The catalog error is returned even when another credential covers the root.
      assertThrows(
          CatalogException.class,
          () ->
              ProtocolIcebergTables.requireCredentialsCoveringRoot(
                  TABLE,
                  List.of(
                      credential("s3://bucket/t/", CloudProvider.AWS),
                      credential(prefix, CloudProvider.AWS)),
                  "s3://bucket/t/"));
    }
  }

  @Test
  void mostSpecificCoveringCredentialsComeFirst() {
    // Keep bucket-wide, auxiliary, and root credentials in catalog order.
    List<StorageCredentials> credentials =
        List.of(
            credential("s3://bucket/", CloudProvider.AWS),
            credential("s3://auxiliary/", CloudProvider.AWS),
            credential("s3://bucket/t/", CloudProvider.AWS));

    // Choose the most specific credential that covers the root.
    List<IcebergStorageCredential> result =
        ProtocolIcebergTables.requireCredentialsCoveringRoot(TABLE, credentials, "s3://bucket/t/");

    // All remaining credentials retain their catalog order.
    assertEquals(
        List.of("s3://bucket/t/", "s3://bucket/", "s3://auxiliary/"),
        result.stream().map(IcebergStorageCredential::prefix).toList());
  }

  @Test
  void rejectsCredentialsThatDoNotCoverTheRoot() {
    // A similarly named directory must not cover the table root.
    List<StorageCredentials> credentials =
        List.of(credential("s3://bucket/two/", CloudProvider.AWS));

    // A response without a covering credential is a catalog error.
    assertThrows(
        CatalogException.class,
        () ->
            ProtocolIcebergTables.requireCredentialsCoveringRoot(
                TABLE, credentials, "s3://bucket/t/"));
  }

  @Test
  void r2RootsAndCredentialsUseIcebergsS3Paths() {
    // Catalog R2 credentials retain their account in the authority.
    List<StorageCredentials> credentials =
        List.of(
            credential("r2://bucket@acct.r2.cloudflarestorage.com/", CloudProvider.R2),
            credential("r2://bucket@acct.r2.cloudflarestorage.com/t/", CloudProvider.R2));

    // Compare the catalog's R2 root in the same path scheme as its credentials.
    List<IcebergStorageCredential> result =
        ProtocolIcebergTables.requireCredentialsCoveringRoot(
            TABLE, credentials, "r2://bucket@acct.r2.cloudflarestorage.com/t/");

    // S3FileIO needs s3:// credential prefixes, with the root-specific one first.
    assertEquals(
        List.of("s3://bucket/t/", "s3://bucket/"),
        result.stream().map(IcebergStorageCredential::prefix).toList());
    // Already normalized credential prefixes also cover an R2 catalog root.
    assertEquals(
        "s3://bucket/t/",
        ProtocolIcebergTables.requireCredentialsCoveringRoot(
                TABLE,
                List.of(credential("s3://bucket/t/", CloudProvider.AWS)),
                "r2://bucket@acct.r2.cloudflarestorage.com/t/")
            .get(0)
            .prefix());
  }

  @Test
  void specificityUsesNormalizedPathLengths() {
    // An R2 account authority is longer but less specific than the S3 table prefix.
    List<StorageCredentials> credentials =
        List.of(
            credential("r2://bucket@acct.r2.cloudflarestorage.com/", CloudProvider.R2),
            credential("s3://bucket/t/", CloudProvider.AWS));

    // Compare directory depths without counting the R2 account authority.
    List<IcebergStorageCredential> result =
        ProtocolIcebergTables.requireCredentialsCoveringRoot(
            TABLE, credentials, "r2://bucket@acct.r2.cloudflarestorage.com/t/");

    // The table-specific credential remains first across mixed path schemes.
    assertEquals(
        List.of("s3://bucket/t/", "s3://bucket/"),
        result.stream().map(IcebergStorageCredential::prefix).toList());
  }

  @Test
  void loadsS3MetadataUnderAnR2Root() {
    // Iceberg writes s3:// metadata locations for R2 tables.
    ResolvedAsset resolved =
        ResolvedAsset.builder(AssetType.TABLE, "main.lake.trips")
            .location(new AssetLocation("r2://bucket@acct.r2.cloudflarestorage.com/t/", List.of()))
            .additionalProperties(
                new TableProperties(
                    DataSourceFormat.ICEBERG,
                    "s3://bucket/t/metadata/v1.json",
                    "{}",
                    List.of(
                        credential(
                            "r2://bucket@acct.r2.cloudflarestorage.com/t/", CloudProvider.R2)),
                    Map.of()))
            .build();
    // Isolate storage-path validation from authorization and catalog I/O.
    ProtocolIcebergTables tables = tablesResolving(resolved);

    // Metadata and vended credentials must both pass their R2 coverage checks.
    IcebergTable result = tables.loadTable(null, "share", "lake", "trips", true);

    // The metadata remains at the location written by Iceberg.
    assertEquals("s3://bucket/t/metadata/v1.json", result.metadataLocation());
    // The returned credentials match the file IO path scheme.
    assertEquals("s3://bucket/t/", result.credentials().get(0).prefix());
  }

  private static StorageCredentials credential(String prefix, CloudProvider provider) {
    return new StorageCredentials(
        prefix,
        provider,
        Map.of(
            StorageCredentials.ACCESS_KEY_ID, "key",
            StorageCredentials.SECRET_ACCESS_KEY, "secret"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static ProtocolIcebergTables tablesResolving(ResolvedAsset resolved) {
    ProtocolShares shares =
        new ProtocolShares(null, null) {
          @Override
          public ShareEntity requireGranted(RecipientPrincipal recipient, String shareName) {
            return new ShareEntity();
          }
        };
    ProtocolTables tables =
        new ProtocolTables(null, null, null, null, null, null, null) {
          @Override
          public ResolvedAsset resolveTable(
              ShareEntity share, String schemaAlias, String tableAlias, ResolveOptions options) {
            return resolved;
          }
        };
    return new ProtocolIcebergTables(shares, null, tables);
  }
}
