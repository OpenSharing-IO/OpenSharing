package io.opensharing.asset.table.signer;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.config.OpenSharingProperties;
import io.opensharing.http.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** GCS V4 signed URLs via {@code Storage.signUrl}. */
@Component
public class GcsUrlSigner implements UrlSigner {

  private final String configuredKeyFile;

  @Autowired
  public GcsUrlSigner(OpenSharingProperties properties) {
    this(
        firstNonBlank(
            properties.getStorage().getGcsServiceAccountKeyFile(),
            System.getenv("GOOGLE_APPLICATION_CREDENTIALS")));
  }

  GcsUrlSigner(String configuredKeyFile) {
    this.configuredKeyFile = configuredKeyFile;
  }

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

  private Storage storage(StorageCredentials credentials) {
    String file =
        firstNonBlank(
            configuredKeyFile,
            credentials == null
                ? null
                : credentials.credentials().get(StorageCredentials.GOOGLE_SERVICE_ACCOUNT_KEY_FILE));
    if (file != null) {
      return StorageOptions.newBuilder()
          .setCredentials(credentialsFromFile(file))
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

  private static String firstNonBlank(String first, String second) {
    if (first != null && !first.isBlank()) {
      return first.trim();
    }
    if (second != null && !second.isBlank()) {
      return second.trim();
    }
    return null;
  }
}
