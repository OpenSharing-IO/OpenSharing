package io.opensharing.catalog.local;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opensharing.auth.AuthContext;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.Asset;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CredentialRequest;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.catalog.StorageOperation;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * ENV credentials list the cloud test table's directory but not the directory above it, and a GCS
 * token for table1 cannot list under the string prefix table10. Cases are skipped unless the cloud
 * credentials are set.
 */
@Timeout(60)
class LocalCatalogEnvCredentialsIntegrationTest {

  private static final AuthContext AUTH =
      AuthContext.of(new UserContext("catalog-alice-id", "alice-token", "alice"));

  private final LocalCatalogConnector catalog =
      new LocalCatalogConnector(LocalCatalogLoader.load("classpath:local-catalog-cloud.yml"));

  @Test
  @EnabledIfEnvironmentVariable(named = "AZURE_TEST_ACCOUNT_KEY", matches = ".+")
  void azureSasListsOnlyTheTableDirectory() throws Exception {
    StorageCredentials credentials = credentials("main.sales.azure");
    URI location = URI.create(credentials.prefix());
    String list =
        "https://"
            + location.getHost()
            + "/"
            + location.getUserInfo()
            + "?resource=filesystem&recursive=false&maxResults=1&"
            + credentials.require(StorageCredentials.SAS_TOKEN)
            + "&directory=";
    String directory = directory(location);

    assertEquals(200, status(list + encode(directory), null));
    assertEquals(403, status(list + encode(parent(directory)), null));
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "GOOGLE_APPLICATION_CREDENTIALS", matches = ".+")
  void googleTokenListsOnlyTheTableDirectory() throws Exception {
    StorageCredentials credentials = credentials("main.sales.gcs");
    URI location = URI.create(credentials.prefix());
    String list =
        "https://storage.googleapis.com/storage/v1/b/"
            + location.getHost()
            + "/o?maxResults=1&prefix=";
    String token = credentials.require(StorageCredentials.OAUTH_TOKEN);
    String directory = directory(location);

    assertEquals(200, status(list + encode(directory + "/"), token));
    // A list prefix of table1, without the slash, would also match table10 on GCS.
    assertEquals(403, status(list + encode(directory), token));
    assertEquals(403, status(list + encode(directory + "0/"), token));
    assertEquals(403, status(list + encode(parent(directory) + "/"), token));
  }

  private StorageCredentials credentials(String fullName) {
    ResolvedAsset table = catalog.resolveAsset(new Asset(AssetType.TABLE, fullName), AUTH);
    return catalog
        .getStorageCredentials(
            new CredentialRequest(
                AssetType.TABLE,
                table.fullName(),
                table.catalogAssetId(),
                table.location().storageLocation(),
                StorageOperation.READ,
                Duration.ofMinutes(15)),
            AUTH)
        .getFirst();
  }

  private static String directory(URI location) {
    return location.getPath().replaceAll("^/+|/+$", "");
  }

  private static String parent(String directory) {
    return directory.substring(0, directory.lastIndexOf('/'));
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static int status(String url, String bearer) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET();
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    return HttpClient.newHttpClient()
        .send(request.build(), BodyHandlers.discarding())
        .statusCode();
  }
}
