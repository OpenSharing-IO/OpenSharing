package io.opensharing.asset.table.signer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.StorageCredentials;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GcsUrlSignerTest {

  @TempDir Path tempDir;

  @Test
  void signsGsPathsWithCatalogServiceAccountKey() throws Exception {
    Path keyFile = writeServiceAccount(tempDir.resolve("sa.json"));
    GcsUrlSigner signer = new GcsUrlSigner((GcsSigningKey) null);
    StorageCredentials credentials =
        new StorageCredentials(
            "gs://bucket/",
            CloudProvider.GCP,
            Map.of(StorageCredentials.GOOGLE_SERVICE_ACCOUNT_KEY_FILE, keyFile.toString()),
            Instant.now().plus(Duration.ofHours(1)));

    SignedUrl signed =
        signer.sign("gs://bucket/path/file.parquet", credentials, Duration.ofMinutes(10));

    assertTrue(signed.url().startsWith("https://storage.googleapis.com/bucket/path/file.parquet?"));
    assertTrue(signed.url().contains("X-Goog-Algorithm=GOOG4-RSA-SHA256"));
    assertTrue(signed.url().contains("X-Goog-Signature="));
  }

  private static Path writeServiceAccount(Path file) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair pair = generator.generateKeyPair();
    String keyType = "PRIVATE" + " KEY";
    String pem =
        "-----BEGIN " + keyType + "-----\n"
            + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())
            + "\n-----END " + keyType + "-----\n";
    ObjectNode json = new ObjectMapper().createObjectNode();
    json.put("client_email", "signer@example.iam.gserviceaccount.com");
    json.put("private_key", pem);
    new ObjectMapper().writeValue(file.toFile(), json);
    return file;
  }
}
