package io.opensharing.asset.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GcsSigningKeyTest {

  @TempDir Path directory;

  @Test
  void readsTheKeyFileItIsPointedAt() throws Exception {
    Path file = keyFile("sharing@example.iam.gserviceaccount.com");

    GcsSigningKey key = GcsSigningKey.configured(file.toString(), null);

    assertEquals("sharing@example.iam.gserviceaccount.com", key.clientEmail());
    assertEquals("RSA", key.privateKey().getAlgorithm());
  }

  /** How the reference sharing server is pointed at a key, so a deployment can be moved unchanged. */
  @Test
  void fallsBackToTheEnvironmentGoogleLibrariesRead() throws Exception {
    Path file = keyFile("from-the-environment@example.iam.gserviceaccount.com");

    GcsSigningKey key = GcsSigningKey.configured("  ", file.toString());

    assertEquals("from-the-environment@example.iam.gserviceaccount.com", key.clientEmail());
  }

  /** No key is a deployment that never asked to sign for Google storage, not a misconfigured one. */
  @Test
  void staysAbsentWhenNothingNamesAKey() {
    assertNull(GcsSigningKey.configured(null, null));
    assertNull(GcsSigningKey.configured("", "  "));
  }

  /** But a key the configuration named and cannot use is a mistake worth hearing about at startup. */
  @Test
  void refusesAConfiguredKeyItCannotUse() throws Exception {
    Path missing = directory.resolve("absent.json");
    Path notAKey = Files.writeString(directory.resolve("other.json"), "{\"type\":\"authorized_user\"}");
    Path badPem =
        Files.writeString(
            directory.resolve("bad-pem.json"),
            "{\"client_email\":\"a@b.iam.gserviceaccount.com\",\"private_key\":\"not-a-key\"}");

    assertTrue(
        assertThrows(IllegalStateException.class, () -> GcsSigningKey.configured(missing.toString(), null))
            .getMessage()
            .contains("cannot be read"));
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () -> GcsSigningKey.configured(notAKey.toString(), null))
            .getMessage()
            .contains("client_email"));
    assertTrue(
        assertThrows(
                IllegalStateException.class, () -> GcsSigningKey.configured(badPem.toString(), null))
            .getMessage()
            .contains("PKCS#8"));
  }

  /**
   * The environment variable is held to a lower standard, because it is ambient: what {@code gcloud
   * auth application-default login} writes there is a user credential with no private key in it, and
   * a server sharing nothing on Google storage must still start on a machine that has one exported.
   */
  @Test
  void passesOverAnAmbientCredentialThatCannotSign() throws Exception {
    Path userCredential =
        Files.writeString(
            directory.resolve("adc.json"),
            "{\"type\":\"authorized_user\",\"refresh_token\":\"x\",\"client_id\":\"y\"}");

    assertNull(GcsSigningKey.configured(null, userCredential.toString()));
    assertNull(GcsSigningKey.configured(null, directory.resolve("absent.json").toString()));
  }

  private Path keyFile(String email) throws IOException, java.security.NoSuchAlgorithmException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    String pem =
        "-----BEGIN PRIVATE KEY-----\\n"
            + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
            + "\\n-----END PRIVATE KEY-----\\n";
    return Files.writeString(
        directory.resolve("key.json"),
        "{\"type\":\"service_account\",\"client_email\":\""
            + email
            + "\",\"private_key\":\""
            + pem
            + "\"}",
        StandardCharsets.UTF_8);
  }
}
