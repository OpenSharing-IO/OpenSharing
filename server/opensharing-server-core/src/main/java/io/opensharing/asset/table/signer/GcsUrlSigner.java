package io.opensharing.asset.table.signer;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.http.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** GCS V4 signed URLs via {@code Storage.signUrl}. */
final class GcsUrlSigner implements UrlSigner {

  @Override
  public Set<String> schemes() {
    return Set.of("gs");
  }

  @Override
  public SignedUrl sign(String path, StorageCredentials credentials, Duration ttl) {
    String[] bucketAndObject = StoragePaths.bucketAndKey(path);
    Instant expiration = Instant.now().plus(ttl);
    String url =
        storage(credentials)
            .signUrl(
                BlobInfo.newBuilder(BlobId.of(bucketAndObject[0], bucketAndObject[1])).build(),
                ttl.toSeconds(),
                TimeUnit.SECONDS,
                Storage.SignUrlOption.withV4Signature())
            .toString();
    return new SignedUrl(url, expiration);
  }

  /**
   * Signs with the service account key file the catalog vends, if any, and otherwise with Google
   * application default credentials.
   */
  private static Storage storage(StorageCredentials credentials) {
    String file =
        credentials == null
            ? null
            : credentials.credentials().get(StorageCredentials.GOOGLE_SERVICE_ACCOUNT_KEY_FILE);
    if (file != null && !file.isBlank()) {
      return StorageOptions.newBuilder()
          .setCredentials(credentialsFromFile(file.trim()))
          .build()
          .getService();
    }
    try {
      return StorageOptions.getDefaultInstance().getService();
    } catch (RuntimeException e) {
      throw ApiException.notImplemented(
          "no Google service account key is configured to sign gs urls");
    }
  }

  private static GoogleCredentials credentialsFromFile(String file) {
    try (InputStream in = Files.newInputStream(Path.of(file))) {
      return ServiceAccountCredentials.fromStream(in);
    } catch (IOException | RuntimeException e) {
      throw new IllegalStateException(
          "the Google service account key file '" + file + "' cannot be read", e);
    }
  }
}
