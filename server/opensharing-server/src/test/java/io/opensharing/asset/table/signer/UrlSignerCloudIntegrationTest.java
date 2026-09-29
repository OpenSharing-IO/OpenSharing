package io.opensharing.asset.table.signer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.delta.kernel.Scan;
import io.delta.kernel.Snapshot;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.utils.CloseableIterator;
import io.opensharing.asset.table.delta.DeltaKernel;
import io.opensharing.auth.AuthContext;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.AssetLookup;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.CredentialRequest;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.catalog.StorageOperation;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Signs a real data file from each cloud test table using catalog-vended ENV credentials, then
 * reads the first byte through the signed URL. Cases are skipped unless those credentials are set.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:url-signer-cloud;DB_CLOSE_DELAY=-1",
      "opensharing.test.stub-protocol-dependencies=false"
    })
@TestPropertySource(properties = "opensharing.catalog.local.file=classpath:local-catalog-cloud.yml")
@Timeout(60)
class UrlSignerCloudIntegrationTest {

  private static final AuthContext AUTH =
      AuthContext.of(new UserContext("catalog-alice-id", "alice-token", "alice"));

  @Autowired private CatalogConnector catalog;
  @Autowired private DeltaKernel kernel;
  @Autowired private UrlSigners signers;

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
  void signsAndReadsARealS3File() throws Exception {
    signAndRead("main.sales.table1", "X-Amz-Signature=");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AZURE_TEST_ACCOUNT_KEY", matches = ".+")
  void signsAndReadsARealAzureFile() throws Exception {
    signAndRead("main.sales.azure", "sig=");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "GOOGLE_APPLICATION_CREDENTIALS", matches = ".+")
  void signsAndReadsARealGcsFile() throws Exception {
    signAndRead("main.sales.gcs", "X-Goog-Signature=");
  }

  private void signAndRead(String identifier, String signatureMarker) throws Exception {
    ResolvedAsset table =
        catalog.resolveAsset(AssetLookup.of(AssetType.TABLE, identifier), AUTH);
    DeltaKernel.Session session = kernel.open(table, AUTH);
    String path = firstDataFile(session);
    StorageCredentials credentials =
        catalog
            .getStorageCredentials(
                new CredentialRequest(
                    AssetType.TABLE,
                    table.identifier(),
                    table.catalogAssetId(),
                    table.storageLocation(),
                    StorageOperation.READ,
                    Duration.ofMinutes(15)),
                AUTH)
            .getFirst();
    SignedUrl signed = signers.sign(path, credentials, Duration.ofMinutes(5));
    assertTrue(signed.url().startsWith("https://"), signed.url());
    assertTrue(signed.url().contains(signatureMarker), signed.url());
    int status = getFirstByte(signed.url());
    assertTrue(status == 200 || status == 206, "HTTP " + status + " for signed " + path);
  }

  private static String firstDataFile(DeltaKernel.Session session) throws IOException {
    Snapshot snapshot = session.table().getLatestSnapshot(session.engine());
    Scan scan = snapshot.getScanBuilder().build();
    try (CloseableIterator<FilteredColumnarBatch> batches = scan.getScanFiles(session.engine())) {
      while (batches.hasNext()) {
        try (CloseableIterator<Row> rows = batches.next().getRows()) {
          while (rows.hasNext()) {
            return InternalScanFileUtils.getAddFileStatus(rows.next()).getPath();
          }
        }
      }
    }
    throw new AssertionError("table at " + session.location() + " has no data files to sign");
  }

  private static int getFirstByte(String url) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Range", "bytes=0-0")
            .GET()
            .build();
    HttpResponse<Void> response =
        HttpClient.newHttpClient().send(request, BodyHandlers.discarding());
    return response.statusCode();
  }
}
